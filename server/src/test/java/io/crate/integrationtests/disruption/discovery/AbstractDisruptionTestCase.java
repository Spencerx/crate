/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.integrationtests.disruption.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.coordination.FollowersChecker;
import org.elasticsearch.cluster.coordination.JoinHelper;
import org.elasticsearch.cluster.coordination.LeaderChecker;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.IntegTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.elasticsearch.test.TestCluster;
import org.elasticsearch.test.disruption.NetworkDisruption;
import org.elasticsearch.test.disruption.NetworkDisruption.Bridge;
import org.elasticsearch.test.disruption.NetworkDisruption.DisruptedLinks;
import org.elasticsearch.test.disruption.NetworkDisruption.NetworkDisconnect;
import org.elasticsearch.test.disruption.NetworkDisruption.NetworkLinkDisruptionType;
import org.elasticsearch.test.disruption.NetworkDisruption.TwoPartitions;
import org.elasticsearch.test.disruption.ServiceDisruptionScheme;
import org.elasticsearch.test.disruption.SlowClusterStateProcessing;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.TransportSettings;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;

import io.crate.common.unit.TimeValue;

public abstract class AbstractDisruptionTestCase extends IntegTestCase {

    static final TimeValue DISRUPTION_HEALING_OVERHEAD = TimeValue.timeValueSeconds(40);

    public static final Settings DEFAULT_SETTINGS = Settings.builder()
        .put(LeaderChecker.LEADER_CHECK_TIMEOUT_SETTING.getKey(), "5s") // for hitting simulated network failures quickly
        .put(LeaderChecker.LEADER_CHECK_RETRY_COUNT_SETTING.getKey(), 1) // for hitting simulated network failures quickly
        .put(FollowersChecker.FOLLOWER_CHECK_TIMEOUT_SETTING.getKey(), "5s") // for hitting simulated network failures quickly
        .put(FollowersChecker.FOLLOWER_CHECK_RETRY_COUNT_SETTING.getKey(), 1) // for hitting simulated network failures quickly
        .put(JoinHelper.JOIN_TIMEOUT_SETTING.getKey(), "10s") // still long to induce failures but not too long so test won't time out
        .put(Coordinator.PUBLISH_TIMEOUT_SETTING.getKey(), "5s") // <-- for hitting simulated network failures quickly
        .put(TransportSettings.CONNECT_TIMEOUT.getKey(), "10s") // Network delay disruption waits for the min between this
        // value and the time of disruption and does not recover immediately
        // when disruption is stopped. We should make sure we recover faster
        // than the default of 30s, which would cause ensureGreen and friends to time out
        .build();

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal)).put(DEFAULT_SETTINGS).build();
    }

    @Override
    public Settings indexSettings() {
        return Settings.builder().put(super.indexSettings())
            // sync global checkpoint quickly so we can verify seq_no_stats aligned between all copies after tests.
            .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "1s").build();
    }

    @Override
    protected int numberOfShards() {
        return 3;
    }

    @Override
    protected int numberOfReplicas() {
        return 1;
    }

    private boolean disableBeforeIndexDeletion;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        disableBeforeIndexDeletion = false;
    }

    @Override
    public void setDisruptionScheme(ServiceDisruptionScheme scheme) {
        if (scheme instanceof NetworkDisruption &&
            ((NetworkDisruption) scheme).getNetworkLinkDisruptionType() instanceof NetworkDisruption.NetworkUnresponsive) {
            // the network unresponsive disruption may leave operations in flight
            // this is because this disruption scheme swallows requests by design
            // as such, these operations will never be marked as finished
            disableBeforeIndexDeletion = true;
        }
        super.setDisruptionScheme(scheme);
    }

    @Override
    protected void beforeIndexDeletion() throws Exception {
        if (disableBeforeIndexDeletion == false) {
            super.beforeIndexDeletion();
            cluster().assertConsistentHistoryBetweenTranslogAndLuceneIndex();
            cluster().assertSeqNos();
            cluster().assertSameDocIdsOnShards();
        }
    }

    List<String> startCluster(int numberOfNodes) {
        TestCluster internalCluster = cluster();
        List<String> nodes = internalCluster.startNodes(numberOfNodes);
        ensureStableCluster(numberOfNodes);
        return nodes;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        final HashSet<Class<? extends Plugin>> classes = new HashSet<>(super.nodePlugins());
        classes.add(MockTransportService.TestPlugin.class);
        classes.add(InternalSettingsPlugin.class);
        return classes;
    }

    ClusterState getNodeClusterState(String node) {
        return FutureUtils.get(client(node).state(new ClusterStateRequest().local(true))).getState();
    }

    void assertNoMaster(final String node) throws Exception {
        assertNoMaster(node, null, TimeValue.timeValueSeconds(30));
    }

    void assertNoMaster(final String node, TimeValue maxWaitTime) throws Exception {
        assertNoMaster(node, null, maxWaitTime);
    }

    void assertNoMaster(final String node, @Nullable final ClusterBlock expectedBlocks, TimeValue maxWaitTime) throws Exception {
        assertBusy(() -> {
            ClusterState state = getNodeClusterState(node);
            final DiscoveryNodes nodes = state.nodes();
            assertThat(nodes.getMasterNode())
                .as("node [" + node + "] still has [" + nodes.getMasterNode() + "] as master")
                .isNull();
            if (expectedBlocks != null) {
                for (ClusterBlockLevel level : expectedBlocks.levels()) {
                    assertThat(state.blocks().hasGlobalBlockWithLevel(level)).as("node [" + node + "] does have level [" + level + "] in it's blocks").isTrue();
                }
            }
        }, maxWaitTime.millis(), TimeUnit.MILLISECONDS);
    }

    void assertDifferentMaster(final String node, final String oldMasterNode) throws Exception {
        assertBusy(() -> {
            ClusterState state = getNodeClusterState(node);
            String masterNode = null;
            if (state.nodes().getMasterNode() != null) {
                masterNode = state.nodes().getMasterNode().getName();
            }
            logger.trace("[{}] master is [{}]", node, state.nodes().getMasterNode());
            assertThat(oldMasterNode)
                .as("node [" + node + "] still has [" + masterNode + "] as master")
                .isNotEqualTo(masterNode);
        }, 30, TimeUnit.SECONDS);
    }

    void assertMaster(String masterNode, List<String> nodes) throws Exception {
        assertBusy(() -> {
            for (String node : nodes) {
                ClusterState state = getNodeClusterState(node);
                String failMsgSuffix = "cluster_state:\n" + state;
                assertThat(state.nodes().getSize()).as("wrong node count on [" + node + "]. " + failMsgSuffix).isEqualTo(nodes.size());
                String otherMasterNodeName =
                    state.nodes().getMasterNode() != null ? state.nodes().getMasterNode().getName() : null;
                assertThat(otherMasterNodeName).as("wrong master on node [" + node + "]. " + failMsgSuffix).isEqualTo(masterNode);
            }
        });
    }

    public ServiceDisruptionScheme addRandomDisruptionScheme() {
        final DisruptedLinks disruptedLinks;
        if (randomBoolean()) {
            disruptedLinks = TwoPartitions.random(random(), cluster().getNodeNames());
        } else {
            disruptedLinks = Bridge.random(random(), cluster().getNodeNames());
        }
        final NetworkLinkDisruptionType disruptionType;
        switch (randomInt(2)) {
            case 0:
                disruptionType = new NetworkDisruption.NetworkUnresponsive();
                break;
            case 1:
                disruptionType = new NetworkDisconnect();
                break;
            case 2:
                disruptionType = NetworkDisruption.NetworkDelay.random(random());
                break;
            default:
                throw new IllegalArgumentException();
        }
        final ServiceDisruptionScheme scheme;
        if (rarely()) {
            scheme = new SlowClusterStateProcessing(random());
        } else {
            scheme = new NetworkDisruption(disruptedLinks, disruptionType);
        }
        setDisruptionScheme(scheme);
        return scheme;
    }

    NetworkDisruption addRandomDisruptionType(TwoPartitions partitions) {
        final NetworkLinkDisruptionType disruptionType;
        if (randomBoolean()) {
            disruptionType = new NetworkDisruption.NetworkUnresponsive();
        } else {
            disruptionType = new NetworkDisconnect();
        }
        NetworkDisruption partition = new NetworkDisruption(partitions, disruptionType);

        setDisruptionScheme(partition);

        return partition;
    }

    TwoPartitions isolateNode(String isolatedNode) {
        return isolateNode(cluster(), isolatedNode);
    }

    public static TwoPartitions isolateNode(TestCluster cluster, String isolatedNode) {
        Set<String> side1 = new HashSet<>();
        Set<String> side2 = new HashSet<>(Arrays.asList(cluster.getNodeNames()));
        side1.add(isolatedNode);
        side2.remove(isolatedNode);

        return new TwoPartitions(side1, side2);
    }

}

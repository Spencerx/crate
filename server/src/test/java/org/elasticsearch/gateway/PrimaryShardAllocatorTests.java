/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsearch.cluster.routing.UnassignedInfo.Reason.CLUSTER_RECOVERED;
import static org.elasticsearch.cluster.routing.UnassignedInfo.Reason.INDEX_CREATED;
import static org.elasticsearch.cluster.routing.UnassignedInfo.Reason.INDEX_REOPENED;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.CorruptIndexException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.health.ClusterStateHealth;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RecoverySource.SnapshotRecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.UnassignedInfo.AllocationStatus;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.ShardLockObtainFailedException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotShardSizeInfo;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;

public class PrimaryShardAllocatorTests extends ESAllocationTestCase {

    private final ShardId shardId = new ShardId( "test", "test", 0);
    private final DiscoveryNode node1 = newNode("node1");
    private final DiscoveryNode node2 = newNode("node2");
    private final DiscoveryNode node3 = newNode("node3");
    private TestAllocator testAllocator;

    @Before
    public void buildTestAllocator() {
        this.testAllocator = new TestAllocator();
    }

    private void allocateAllUnassigned(final RoutingAllocation allocation) {
        final RoutingNodes.UnassignedShards.UnassignedIterator iterator = allocation.routingNodes().unassigned().iterator();
        while (iterator.hasNext()) {
            testAllocator.allocateUnassigned(iterator.next(), allocation, iterator);
        }
    }

    @Test
    public void testNoProcessPrimaryNotAllocatedBefore() {
        final RoutingAllocation allocation;
        // with old version, we can't know if a shard was allocated before or not
        allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(),
            randomFrom(INDEX_CREATED, CLUSTER_RECOVERED, INDEX_REOPENED));
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isFalse();
        assertThat(allocation.routingNodes().unassigned()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().iterator().next().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when async fetch returns that there is no data, the shard will not be allocated.
     */
    @Test
    public void testNoAsyncFetchData() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED,
            "allocId");
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().ignored().getFirst().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests when the node returns that no data was found for it (null for allocation id),
     * it will be moved to ignore unassigned.
     */
    @Test
    public void testNoAllocationFound() {
        final RoutingAllocation allocation =
            routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED, "allocId");
        testAllocator.addData(node1, null, randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().ignored().getFirst().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests when the node returns data with a shard allocation id that does not match active allocation ids, it will be moved to ignore
     * unassigned.
     */
    @Test
    public void testNoMatchingAllocationIdFound() {
        RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED, "id2");
        testAllocator.addData(node1, "id1", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().ignored().getFirst().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests when the node returns that no data was found for it, it will be moved to ignore unassigned.
     */
    @Test
    public void testStoreException() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED,
            "allocId1");
        testAllocator.addData(node1, "allocId1", randomBoolean(), new CorruptIndexException("test", "test"));
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().ignored().getFirst().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when the node returns a ShardLockObtainFailedException, it will be considered as a valid shard copy
     */
    @Test
    public void testShardLockObtainFailedException() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED,
            "allocId1");
        testAllocator.addData(node1, "allocId1", randomBoolean(), new ShardLockObtainFailedException(shardId, "test"));
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().currentNodeId()).isEqualTo(node1.getId());
        // check that allocation id is reused
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().allocationId().getId()).isEqualTo("allocId1");
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when one node returns a ShardLockObtainFailedException and another properly loads the store, it will
     * select the second node as target
     */
    @Test
    public void testShardLockObtainFailedExceptionPreferOtherValidCopies() {
        String allocId1 = randomAlphaOfLength(10);
        String allocId2 = randomAlphaOfLength(10);
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(), CLUSTER_RECOVERED,
            allocId1, allocId2);
        testAllocator.addData(node1, allocId1, randomBoolean(),
            new ShardLockObtainFailedException(shardId, "test"));
        testAllocator.addData(node2, allocId2, randomBoolean(), null);
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().currentNodeId()).isEqualTo(node2.getId());
        // check that allocation id is reused
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().allocationId().getId()).isEqualTo(allocId2);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when there is a node to allocate the shard to, it will be allocated to it.
     */
    @Test
    public void testFoundAllocationAndAllocating() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(),
            randomFrom(CLUSTER_RECOVERED, INDEX_REOPENED), "allocId1");
        testAllocator.addData(node1, "allocId1", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().currentNodeId()).isEqualTo(node1.getId());
        // check that allocation id is reused
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().allocationId().getId()).isEqualTo("allocId1");
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when the nodes with prior copies of the given shard all return a decision of NO, but
     * {@link AllocationDecider#canForceAllocatePrimary(ShardRouting, RoutingNode, RoutingAllocation)}
     * returns a YES decision for at least one of those NO nodes, then we force allocate to one of them
     */
    @Test
    public void testForceAllocatePrimary() {
        testAllocator.addData(node1, "allocId1", randomBoolean());
        AllocationDeciders deciders = new AllocationDeciders(Arrays.asList(
            // since the deciders return a NO decision for allocating a shard (due to the guaranteed NO decision from the second decider),
            // the allocator will see if it can force assign the primary, where the decision will be YES
            new TestAllocateDecision(randomBoolean() ? Decision.YES : Decision.NO), getNoDeciderThatAllowsForceAllocate()
        ));
        RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(deciders, CLUSTER_RECOVERED, "allocId1");
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertThat(node1.getId()).isEqualTo(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).get(0).currentNodeId());
    }

    /**
     * Tests that when the nodes with prior copies of the given shard all return a decision of NO, and
     * {@link AllocationDecider#canForceAllocatePrimary(ShardRouting, RoutingNode, RoutingAllocation)}
     * returns a NO or THROTTLE decision for a node, then we do not force allocate to that node.
     */
    @Test
    public void testDontAllocateOnNoOrThrottleForceAllocationDecision() {
        testAllocator.addData(node1, "allocId1", randomBoolean());
        boolean forceDecisionNo = randomBoolean();
        AllocationDeciders deciders = new AllocationDeciders(Arrays.asList(
            // since both deciders here return a NO decision for allocating a shard,
            // the allocator will see if it can force assign the primary, where the decision will be either NO or THROTTLE,
            // so the shard will remain un-initialized
            new TestAllocateDecision(Decision.NO), forceDecisionNo ? getNoDeciderThatDeniesForceAllocate() :
                                                                     getNoDeciderThatThrottlesForceAllocate()
        ));
        RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(deciders, CLUSTER_RECOVERED, "allocId1");
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        List<ShardRouting> ignored = allocation.routingNodes().unassigned().ignored();
        assertThat(ignored).hasSize(1);
        assertThat(forceDecisionNo ? AllocationStatus.DECIDERS_NO : AllocationStatus.DECIDERS_THROTTLED).isEqualTo(ignored.get(0).unassignedInfo().getLastAllocationStatus());
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).isEmpty()).isTrue();
    }

    /**
     * Tests that when the nodes with prior copies of the given shard return a THROTTLE decision,
     * then we do not force allocate to that node but instead throttle.
     */
    @Test
    public void testDontForceAllocateOnThrottleDecision() {
        testAllocator.addData(node1, "allocId1", randomBoolean());
        AllocationDeciders deciders = new AllocationDeciders(Arrays.asList(
            // since we have a NO decision for allocating a shard (because the second decider returns a NO decision),
            // the allocator will see if it can force assign the primary, and in this case,
            // the TestAllocateDecision's decision for force allocating is to THROTTLE (using
            // the default behavior) so despite the other decider's decision to return YES for
            // force allocating the shard, we still THROTTLE due to the decision from TestAllocateDecision
            new TestAllocateDecision(Decision.THROTTLE), getNoDeciderThatAllowsForceAllocate()
        ));
        RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(deciders, CLUSTER_RECOVERED, "allocId1");
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        List<ShardRouting> ignored = allocation.routingNodes().unassigned().ignored();
        assertThat(ignored).hasSize(1);
        assertThat(AllocationStatus.DECIDERS_THROTTLED).isEqualTo(ignored.get(0).unassignedInfo().getLastAllocationStatus());
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).isEmpty()).isTrue();
    }

    /**
     * Tests that when there was a node that previously had the primary, it will be allocated to that same node again.
     */
    @Test
    public void testPreferAllocatingPreviousPrimary() {
        String primaryAllocId = UUIDs.randomBase64UUID();
        String replicaAllocId = UUIDs.randomBase64UUID();
        RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(yesAllocationDeciders(),
            randomFrom(CLUSTER_RECOVERED, INDEX_REOPENED), primaryAllocId, replicaAllocId);
        boolean node1HasPrimaryShard = randomBoolean();
        testAllocator.addData(node1, node1HasPrimaryShard ? primaryAllocId : replicaAllocId, node1HasPrimaryShard);
        testAllocator.addData(node2, node1HasPrimaryShard ? replicaAllocId : primaryAllocId, !node1HasPrimaryShard);
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        DiscoveryNode allocatedNode = node1HasPrimaryShard ? node1 : node2;
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().currentNodeId()).isEqualTo(allocatedNode.getId());
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when there is a node to allocate to, but it is throttling (and it is the only one),
     * it will be moved to ignore unassigned until it can be allocated to.
     */
    @Test
    public void testFoundAllocationButThrottlingDecider() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(throttleAllocationDeciders(), CLUSTER_RECOVERED,
            "allocId1");
        testAllocator.addData(node1, "allocId1", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored()).hasSize(1);
        assertThat(allocation.routingNodes().unassigned().ignored().getFirst().shardId()).isEqualTo(shardId);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when there is a node to be allocated to, but it the decider said "no", we still
     * force the allocation to it.
     */
    @Test
    public void testFoundAllocationButNoDecider() {
        final RoutingAllocation allocation = routingAllocationWithOnePrimaryNoReplicas(noAllocationDeciders(), CLUSTER_RECOVERED,
            "allocId1");
        testAllocator.addData(node1, "allocId1", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).getFirst().currentNodeId()).isEqualTo(node1.getId());
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when restoring from a snapshot and we find a node with a shard copy and allocation
     * deciders say yes, we allocate to that node.
     */
    @Test
    public void testRestore() {
        RoutingAllocation allocation = getRestoreRoutingAllocation(yesAllocationDeciders(), randomLong(), "allocId");
        testAllocator.addData(node1, "some allocId", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING)).hasSize(1);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when restoring from a snapshot and we find a node with a shard copy and allocation
     * deciders say throttle, we add it to ignored shards.
     */
    @Test
    public void testRestoreThrottle() {
        RoutingAllocation allocation = getRestoreRoutingAllocation(throttleAllocationDeciders(), randomLong(), "allocId");
        testAllocator.addData(node1, "some allocId", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isFalse();
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when restoring from a snapshot and we find a node with a shard copy but allocation
     * deciders say no, we still allocate to that node.
     */
    @Test
    public void testRestoreForcesAllocateIfShardAvailable() {
        final long shardSize = randomNonNegativeLong();
        RoutingAllocation allocation = getRestoreRoutingAllocation(noAllocationDeciders(), shardSize, "allocId");
        testAllocator.addData(node1, "some allocId", randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        final List<ShardRouting> initializingShards = allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING);
        assertThat(initializingShards).hasSize(1);
        assertThat(initializingShards.getFirst().getExpectedShardSize()).isEqualTo(shardSize);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when restoring from a snapshot and we don't find a node with a shard copy, the shard will remain in
     * the unassigned list to be allocated later.
     */
    @Test
    public void testRestoreDoesNotAssignIfNoShardAvailable() {
        RoutingAllocation allocation = getRestoreRoutingAllocation(yesAllocationDeciders(), randomNonNegativeLong(), "allocId");
        testAllocator.addData(node1, null, randomBoolean());
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isFalse();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isTrue();
        assertThat(allocation.routingNodes().unassigned()).hasSize(1);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    /**
     * Tests that when restoring from a snapshot and we don't know the shard size yet, the shard will remain in
     * the unassigned list to be allocated later.
     */
    @Test
    public void testRestoreDoesNotAssignIfShardSizeNotAvailable() {
        RoutingAllocation allocation = getRestoreRoutingAllocation(yesAllocationDeciders(), null, "allocId");
        testAllocator.addData(node1, null, false);
        allocateAllUnassigned(allocation);
        assertThat(allocation.routingNodesChanged()).isTrue();
        assertThat(allocation.routingNodes().unassigned().ignored().isEmpty()).isFalse();
        ShardRouting ignoredRouting = allocation.routingNodes().unassigned().ignored().getFirst();
        assertThat(ignoredRouting.unassignedInfo().getLastAllocationStatus()).isEqualTo(AllocationStatus.FETCHING_SHARD_DATA);
        assertClusterHealthStatus(allocation, ClusterHealthStatus.YELLOW);
    }

    private RoutingAllocation getRestoreRoutingAllocation(AllocationDeciders allocationDeciders, Long shardSize, String... allocIds) {
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder(shardId.getIndexUUID()).settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0)
                .putInSyncAllocationIds(0, Set.of(allocIds)))
            .build();

        final Snapshot snapshot = new Snapshot("test", new SnapshotId("test", UUIDs.randomBase64UUID()));
        RoutingTable routingTable = RoutingTable.builder()
            .addAsRestore(metadata.index(shardId.getIndex()),
                new SnapshotRecoverySource(UUIDs.randomBase64UUID(), snapshot, Version.CURRENT,
                    new IndexId(shardId.getIndexName(), shardId.getIndexUUID())))
            .build();
        ClusterState state = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3)).build();
        return new RoutingAllocation(allocationDeciders, new RoutingNodes(state, false), state, null,
            new SnapshotShardSizeInfo(ImmutableOpenMap.of()) {
                @Override
                public Long getShardSize(ShardRouting shardRouting) {
                    return shardSize;
                }
            }, System.nanoTime());
    }

    private RoutingAllocation routingAllocationWithOnePrimaryNoReplicas(AllocationDeciders deciders, UnassignedInfo.Reason reason,
                                                                        String... activeAllocationIds) {
        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder(shardId.getIndexUUID()).settings(settings(Version.CURRENT))
                    .numberOfShards(1).numberOfReplicas(0).putInSyncAllocationIds(shardId.id(), Set.of(activeAllocationIds)))
                .build();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        switch (reason) {

            case INDEX_CREATED:
                routingTableBuilder.addAsNew(metadata.index(shardId.getIndex()));
                break;
            case CLUSTER_RECOVERED:
                routingTableBuilder.addAsRecovery(metadata.index(shardId.getIndex()));
                break;
            case INDEX_REOPENED:
                routingTableBuilder.addAsFromCloseToOpen(metadata.index(shardId.getIndex()));
                break;
            default:
                throw new IllegalArgumentException("can't do " + reason + " for you. teach me");
        }
        ClusterState state = ClusterState.builder(org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
                .metadata(metadata)
                .routingTable(routingTableBuilder.build())
                .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3)).build();
        return new RoutingAllocation(deciders, new RoutingNodes(state, false), state, null, null, System.nanoTime());
    }

    private void assertClusterHealthStatus(RoutingAllocation allocation, ClusterHealthStatus expectedStatus) {
        RoutingTable oldRoutingTable = allocation.routingTable();
        RoutingNodes newRoutingNodes = allocation.routingNodes();
        final RoutingTable newRoutingTable = new RoutingTable.Builder()
                                                             .updateNodes(oldRoutingTable.version(), newRoutingNodes)
                                                             .build();
        ClusterState clusterState = ClusterState.builder(new ClusterName("test-cluster"))
                                                .routingTable(newRoutingTable)
                                                .build();
        ClusterStateHealth clusterStateHealth = new ClusterStateHealth(clusterState);
        assertThat(clusterStateHealth.getStatus().ordinal()).isLessThanOrEqualTo(expectedStatus.ordinal());
    }

    private AllocationDecider getNoDeciderThatAllowsForceAllocate() {
        return getNoDeciderWithForceAllocate(Decision.YES);
    }

    private AllocationDecider getNoDeciderThatThrottlesForceAllocate() {
        return getNoDeciderWithForceAllocate(Decision.THROTTLE);
    }

    private AllocationDecider getNoDeciderThatDeniesForceAllocate() {
        return getNoDeciderWithForceAllocate(Decision.NO);
    }

    private AllocationDecider getNoDeciderWithForceAllocate(final Decision forceAllocateDecision) {
        return new TestAllocateDecision(Decision.NO) {
            @Override
            public Decision canForceAllocatePrimary(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
                assert shardRouting.primary() : "cannot force allocate a non-primary shard " + shardRouting;
                return forceAllocateDecision;
            }
        };
    }

    class TestAllocator extends PrimaryShardAllocator {

        private Map<DiscoveryNode, TransportNodesListGatewayStartedShards.NodeGatewayStartedShards> data;

        public TestAllocator clear() {
            data = null;
            return this;
        }

        public TestAllocator addData(DiscoveryNode node, String allocationId, boolean primary) {
            return addData(node, allocationId, primary, null);
        }

        public TestAllocator addData(DiscoveryNode node, String allocationId, boolean primary, @Nullable Exception storeException) {
            if (data == null) {
                data = new HashMap<>();
            }
            data.put(node,
                new TransportNodesListGatewayStartedShards.NodeGatewayStartedShards(node, allocationId, primary, storeException));
            return this;
        }

        @Override
        protected AsyncShardFetch.FetchResult<TransportNodesListGatewayStartedShards.NodeGatewayStartedShards>
                                                                        fetchData(ShardRouting shard, RoutingAllocation allocation) {
            return new AsyncShardFetch.FetchResult<>(shardId, data, Collections.<String>emptySet());
        }
    }
}

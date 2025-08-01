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

package org.elasticsearch.action.support.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.elasticsearch.action.support.replication.ClusterStateCreationUtils.state;
import static org.elasticsearch.action.support.replication.ClusterStateCreationUtils.stateWithActivePrimary;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.UnavailableShardsException;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.PlainFuture;
import org.elasticsearch.action.support.replication.ReplicationResponse.ShardInfo;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.index.shard.IndexShardNotStartedException;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ReplicationGroup;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.node.NodeClosedException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.SendRequestTransportException;
import org.junit.Test;

import io.crate.common.collections.Sets;
import io.crate.common.unit.TimeValue;

public class ReplicationOperationTests extends ESTestCase {

    private ThreadPool threadPool;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
    }

    @Override
    public void tearDown() throws Exception {
        terminate(threadPool);
        super.tearDown();
    }

    @Test
    public void testReplication() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);

        ClusterState initialState = stateWithActivePrimary(index, true, randomInt(5));
        IndexMetadata indexMetadata = initialState.metadata().index(index);
        final long primaryTerm = indexMetadata.primaryTerm(0);
        final IndexShardRoutingTable indexShardRoutingTable = initialState.routingTable().shardRoutingTable(shardId);
        ShardRouting primaryShard = indexShardRoutingTable.primaryShard();
        if (primaryShard.relocating() && randomBoolean()) {
            // simulate execution of the replication phase on the relocation target node after relocation source was marked as relocated
            initialState = ClusterState.builder(initialState)
                .nodes(DiscoveryNodes.builder(initialState.nodes()).localNodeId(primaryShard.relocatingNodeId())).build();
            primaryShard = primaryShard.getTargetRelocatingShard();
        }
        // add a few in-sync allocation ids that don't have corresponding routing entries
        final Set<String> staleAllocationIds = Set.of(generateRandomStringArray(4, 10, false));

        final Set<String> inSyncAllocationIds = Sets.union(indexMetadata.inSyncAllocationIds(0), staleAllocationIds);

        final Set<String> trackedShards = new HashSet<>();
        final Set<String> untrackedShards = new HashSet<>();
        addTrackingInfo(indexShardRoutingTable, primaryShard, trackedShards, untrackedShards);
        trackedShards.addAll(staleAllocationIds);

        final ReplicationGroup replicationGroup = new ReplicationGroup(indexShardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final Set<ShardRouting> expectedReplicas = getExpectedReplicas(shardId, initialState, trackedShards);

        final Map<ShardRouting, Exception> simulatedFailures = new HashMap<>();
        final Map<ShardRouting, Exception> reportedFailures = new HashMap<>();
        for (ShardRouting replica : expectedReplicas) {
            if (randomBoolean()) {
                Exception t;
                boolean criticalFailure = randomBoolean();
                if (criticalFailure) {
                    t = new CorruptIndexException("simulated", (String) null);
                    reportedFailures.put(replica, t);
                } else {
                    t = new IndexShardNotStartedException(shardId, IndexShardState.RECOVERING);
                }
                logger.debug("--> simulating failure on {} with [{}]", replica, t.getClass().getSimpleName());
                simulatedFailures.put(replica, t);
            }
        }

        Request request = new Request(shardId);
        PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final TestReplicaProxy replicasProxy = new TestReplicaProxy(simulatedFailures);

        final TestPrimary primary = new TestPrimary(primaryShard, () -> replicationGroup, threadPool);
        final TestReplicationOperation op = new TestReplicationOperation(
            request,
            primary,
            listener,
            replicasProxy,
            logger,
            threadPool,
            "test",
            primaryTerm
        );
        op.execute();

        assertThat(request.processedOnPrimary.get()).as("request was not processed on primary").isTrue();
        assertThat(request.processedOnReplicas).isEqualTo(expectedReplicas);
        assertThat(replicasProxy.failedReplicas).isEqualTo(simulatedFailures.keySet());
        assertThat(replicasProxy.markedAsStaleCopies).isEqualTo(staleAllocationIds);
        assertThat(request.runPostReplicationActionsOnPrimary.get()).as("post replication operations not run on primary").isTrue();
        assertThat(listener.isDone()).as("listener is not marked as done").isTrue();
        ShardInfo shardInfo = FutureUtils.get(listener).getShardInfo();
        assertThat(shardInfo.getFailed()).isEqualTo(reportedFailures.size());
        assertThat(shardInfo.getFailures()).hasSize(reportedFailures.size());
        assertThat(shardInfo.getSuccessful()).isEqualTo(1 + expectedReplicas.size() - simulatedFailures.size());
        final List<ShardRouting> unassignedShards =
            indexShardRoutingTable.shardsWithState(ShardRoutingState.UNASSIGNED);
        final int totalShards = 1 + expectedReplicas.size() + unassignedShards.size() + untrackedShards.size();
        assertThat(shardInfo.getTotal()).as(replicationGroup.toString()).isEqualTo(totalShards);

        assertThat(primary.knownLocalCheckpoints.remove(primaryShard.allocationId().getId())).isEqualTo(primary.localCheckpoint);
        assertThat(primary.knownLocalCheckpoints).isEqualTo(replicasProxy.generatedLocalCheckpoints);
        assertThat(primary.knownGlobalCheckpoints.remove(primaryShard.allocationId().getId())).isEqualTo(primary.globalCheckpoint);
        assertThat(primary.knownGlobalCheckpoints).isEqualTo(replicasProxy.generatedGlobalCheckpoints);
    }

    public void testRetryTransientReplicationFailure() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);

        ClusterState initialState = stateWithActivePrimary(index, true, randomInt(5));
        IndexMetadata indexMetadata = initialState.metadata().index(index);
        final long primaryTerm = indexMetadata.primaryTerm(0);
        final IndexShardRoutingTable indexShardRoutingTable = initialState.routingTable().shardRoutingTable(shardId);
        ShardRouting primaryShard = indexShardRoutingTable.primaryShard();
        if (primaryShard.relocating() && randomBoolean()) {
            // simulate execution of the replication phase on the relocation target node after relocation source was marked as relocated
            initialState = ClusterState.builder(initialState)
                .nodes(DiscoveryNodes.builder(initialState.nodes()).localNodeId(primaryShard.relocatingNodeId())).build();
            primaryShard = primaryShard.getTargetRelocatingShard();
        }
        // add a few in-sync allocation ids that don't have corresponding routing entries
        final Set<String> staleAllocationIds = Set.of(generateRandomStringArray(4, 10, false));

        final Set<String> inSyncAllocationIds = Sets.union(indexMetadata.inSyncAllocationIds(0), staleAllocationIds);

        final Set<String> trackedShards = new HashSet<>();
        final Set<String> untrackedShards = new HashSet<>();
        addTrackingInfo(indexShardRoutingTable, primaryShard, trackedShards, untrackedShards);
        trackedShards.addAll(staleAllocationIds);

        final ReplicationGroup replicationGroup = new ReplicationGroup(indexShardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final Set<ShardRouting> expectedReplicas = getExpectedReplicas(shardId, initialState, trackedShards);

        final Map<ShardRouting, Exception> simulatedFailures = new HashMap<>();
        for (ShardRouting replica : expectedReplicas) {
            Exception cause;
            Exception exception;
            if (randomBoolean()) {
                if (randomBoolean()) {
                    cause = new CircuitBreakingException("broken");
                } else {
                    cause = new EsRejectedExecutionException("rejected", false);
                }
                exception = new RemoteTransportException("remote", cause);
            } else {
                TransportAddress address = new TransportAddress(InetAddress.getLoopbackAddress(), 9300);
                DiscoveryNode node = new DiscoveryNode("replica", address, Version.CURRENT);
                cause = new ConnectTransportException(node, "broken");
                exception = cause;
            }
            logger.debug("--> simulating failure on {} with [{}]", replica, exception.getClass().getSimpleName());
            simulatedFailures.put(replica, exception);
        }

        Request request = new Request(shardId);
        PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final TestReplicaProxy replicasProxy = new TestReplicaProxy(simulatedFailures, true);

        final TestPrimary primary = new TestPrimary(primaryShard, () -> replicationGroup, threadPool);
        final TestReplicationOperation op = new TestReplicationOperation(request, primary, listener, replicasProxy, primaryTerm,
            TimeValue.timeValueMillis(20), TimeValue.timeValueSeconds(60));
        op.execute();
        assertThat(request.processedOnPrimary.get()).as("request was not processed on primary").isTrue();
        assertThat(request.processedOnReplicas).isEqualTo(expectedReplicas);
        assertThat(replicasProxy.failedReplicas).hasSize(0);
        assertThat(replicasProxy.markedAsStaleCopies).isEqualTo(staleAllocationIds);
        assertThat(request.runPostReplicationActionsOnPrimary.get()).as("post replication operations not run on primary").isTrue();
        ShardInfo shardInfo = FutureUtils.get(listener).getShardInfo();
        assertThat(shardInfo.getSuccessful()).isEqualTo(1 + expectedReplicas.size());
        final List<ShardRouting> unassignedShards = indexShardRoutingTable.shardsWithState(ShardRoutingState.UNASSIGNED);
        final int totalShards = 1 + expectedReplicas.size() + unassignedShards.size() + untrackedShards.size();
        assertThat(shardInfo.getTotal()).as(replicationGroup.toString()).isEqualTo(totalShards);

        assertThat(primary.knownLocalCheckpoints.remove(primaryShard.allocationId().getId())).isEqualTo(primary.localCheckpoint);
        assertThat(primary.knownLocalCheckpoints).isEqualTo(replicasProxy.generatedLocalCheckpoints);
        assertThat(primary.knownGlobalCheckpoints.remove(primaryShard.allocationId().getId())).isEqualTo(primary.globalCheckpoint);
        assertThat(primary.knownGlobalCheckpoints).isEqualTo(replicasProxy.generatedGlobalCheckpoints);
    }

    private void addTrackingInfo(IndexShardRoutingTable indexShardRoutingTable, ShardRouting primaryShard, Set<String> trackedShards,
                                 Set<String> untrackedShards) {
        for (ShardRouting shr : indexShardRoutingTable.shards()) {
            if (shr.unassigned() == false) {
                if (shr.initializing()) {
                    if (randomBoolean()) {
                        trackedShards.add(shr.allocationId().getId());
                    } else {
                        untrackedShards.add(shr.allocationId().getId());
                    }
                } else {
                    trackedShards.add(shr.allocationId().getId());
                    if (shr.relocating()) {
                        if (primaryShard == shr.getTargetRelocatingShard() || randomBoolean()) {
                            trackedShards.add(shr.getTargetRelocatingShard().allocationId().getId());
                        } else {
                            untrackedShards.add(shr.getTargetRelocatingShard().allocationId().getId());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testNoLongerPrimary() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);

        ClusterState initialState = stateWithActivePrimary(index, true, 1 + randomInt(2), randomInt(2));
        IndexMetadata indexMetadata = initialState.metadata().index(index);
        final long primaryTerm = indexMetadata.primaryTerm(0);
        final IndexShardRoutingTable indexShardRoutingTable = initialState.routingTable().shardRoutingTable(shardId);
        ShardRouting primaryShard = indexShardRoutingTable.primaryShard();
        if (primaryShard.relocating() && randomBoolean()) {
            // simulate execution of the replication phase on the relocation target node after relocation source was marked as relocated
            initialState = ClusterState.builder(initialState)
                .nodes(DiscoveryNodes.builder(initialState.nodes()).localNodeId(primaryShard.relocatingNodeId())).build();
            primaryShard = primaryShard.getTargetRelocatingShard();
        }
        // add an in-sync allocation id that doesn't have a corresponding routing entry
        final Set<String> staleAllocationIds = Set.of(randomAlphaOfLength(10));
        final Set<String> inSyncAllocationIds = Sets.union(indexMetadata.inSyncAllocationIds(0), staleAllocationIds);
        final Set<String> trackedShards = new HashSet<>();
        addTrackingInfo(indexShardRoutingTable, primaryShard, trackedShards, new HashSet<>());
        trackedShards.addAll(staleAllocationIds);

        final ReplicationGroup replicationGroup = new ReplicationGroup(indexShardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final Set<ShardRouting> expectedReplicas = getExpectedReplicas(shardId, initialState, trackedShards);

        final Map<ShardRouting, Exception> expectedFailures = new HashMap<>();
        if (expectedReplicas.isEmpty()) {
            return;
        }
        final ShardRouting failedReplica = randomFrom(new ArrayList<>(expectedReplicas));
        expectedFailures.put(failedReplica, new CorruptIndexException("simulated", (String) null));

        Request request = new Request(shardId);
        PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final boolean testPrimaryDemotedOnStaleShardCopies = randomBoolean();
        final Exception shardActionFailure;
        if (randomBoolean()) {
            shardActionFailure = new NodeClosedException(new DiscoveryNode("foo", buildNewFakeTransportAddress(), Version.CURRENT));
        } else if (randomBoolean()) {
            DiscoveryNode node = new DiscoveryNode("foo", buildNewFakeTransportAddress(), Version.CURRENT);
            shardActionFailure = new SendRequestTransportException(
                node, ShardStateAction.SHARD_FAILED_ACTION_NAME, new NodeClosedException(node));
        } else {
            shardActionFailure = new ShardStateAction.NoLongerPrimaryShardException(failedReplica.shardId(), "the king is dead");
        }
        final TestReplicaProxy replicasProxy = new TestReplicaProxy(expectedFailures) {
            @Override
            public void failShardIfNeeded(ShardRouting replica, long primaryTerm, String message, Exception exception,
                                          ActionListener<Void> shardActionListener) {
                if (testPrimaryDemotedOnStaleShardCopies) {
                    super.failShardIfNeeded(replica, primaryTerm, message, exception, shardActionListener);
                } else {
                    assertThat(replica).isEqualTo(failedReplica);
                    shardActionListener.onFailure(shardActionFailure);
                }
            }

            @Override
            public void markShardCopyAsStaleIfNeeded(ShardId shardId, String allocationId, long primaryTerm,
                                                     ActionListener<Void> shardActionListener) {
                if (testPrimaryDemotedOnStaleShardCopies) {
                    shardActionListener.onFailure(shardActionFailure);
                } else {
                    super.markShardCopyAsStaleIfNeeded(shardId, allocationId, primaryTerm, shardActionListener);
                }
            }
        };
        AtomicBoolean primaryFailed = new AtomicBoolean();
        final TestPrimary primary = new TestPrimary(primaryShard, () -> replicationGroup, threadPool) {
            @Override
            public void failShard(String message, Exception exception) {
                assertThat(primaryFailed.compareAndSet(false, true)).isTrue();
            }
        };
        final TestReplicationOperation op = new TestReplicationOperation(
            request, primary, listener, replicasProxy, logger, threadPool, "test", primaryTerm);
        op.execute();

        assertThat(request.processedOnPrimary.get()).as("request was not processed on primary").isTrue();
        assertThat(listener.isDone()).as("listener is not marked as done").isTrue();
        if (shardActionFailure instanceof ShardStateAction.NoLongerPrimaryShardException) {
            assertThat(primaryFailed.get()).isTrue();
        } else {
            assertThat(primaryFailed.get()).isFalse();
        }
        assertListenerThrows("should throw exception to trigger retry", listener,
                             ReplicationOperation.RetryOnPrimaryException.class);
    }

    @Test
    public void testAddedReplicaAfterPrimaryOperation() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);
        final ClusterState initialState = stateWithActivePrimary(index, true, 0);
        Set<String> inSyncAllocationIds = initialState.metadata().index(index).inSyncAllocationIds(0);
        IndexShardRoutingTable shardRoutingTable = initialState.routingTable().shardRoutingTable(shardId);
        Set<String> trackedShards = new HashSet<>();
        addTrackingInfo(shardRoutingTable, null, trackedShards, new HashSet<>());
        ReplicationGroup initialReplicationGroup = new ReplicationGroup(shardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final ClusterState stateWithAddedReplicas;
        if (randomBoolean()) {
            stateWithAddedReplicas = state(index, true, ShardRoutingState.STARTED,
                                           randomBoolean() ? ShardRoutingState.INITIALIZING : ShardRoutingState.STARTED);
        } else {
            stateWithAddedReplicas = state(index, true, ShardRoutingState.RELOCATING);
        }

        inSyncAllocationIds = stateWithAddedReplicas.metadata().index(index).inSyncAllocationIds(0);
        shardRoutingTable = stateWithAddedReplicas.routingTable().shardRoutingTable(shardId);
        trackedShards = new HashSet<>();
        addTrackingInfo(shardRoutingTable, null, trackedShards, new HashSet<>());

        ReplicationGroup updatedReplicationGroup = new ReplicationGroup(shardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final AtomicReference<ReplicationGroup> replicationGroup = new AtomicReference<>(initialReplicationGroup);
        logger.debug("--> using initial replicationGroup:\n{}", replicationGroup.get());
        final long primaryTerm = initialState.metadata().index(shardId.getIndexUUID()).primaryTerm(shardId.id());
        final ShardRouting primaryShard = updatedReplicationGroup.getRoutingTable().primaryShard();
        final TestPrimary primary = new TestPrimary(primaryShard, replicationGroup::get, threadPool) {
            @Override
            public void perform(Request request, ActionListener<Result> listener) {
                super.perform(request, listener.map(result -> {
                    replicationGroup.set(updatedReplicationGroup);
                    logger.debug("--> state after primary operation:\n{}", replicationGroup.get());
                    return result;
                }));
            }
        };

        Request request = new Request(shardId);
        PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final TestReplicationOperation op = new TestReplicationOperation(
            request,
            primary,
            listener,
            new TestReplicaProxy(),
            logger,
            threadPool,
            "tests",
            primaryTerm);
        op.execute();

        assertThat(request.processedOnPrimary.get()).as("request was not processed on primary").isTrue();
        Set<ShardRouting> expectedReplicas = getExpectedReplicas(shardId, stateWithAddedReplicas, trackedShards);
        assertThat(request.processedOnReplicas).isEqualTo(expectedReplicas);
    }

    @Test
    public void testWaitForActiveShards() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);
        final int assignedReplicas = randomInt(2);
        final int unassignedReplicas = randomInt(2);
        final int totalShards = 1 + assignedReplicas + unassignedReplicas;
        final int activeShardCount = randomIntBetween(0, totalShards);
        Request request = new Request(shardId).waitForActiveShards(
            activeShardCount == totalShards ? ActiveShardCount.ALL : ActiveShardCount.from(activeShardCount));
        final boolean passesActiveShardCheck = activeShardCount <= assignedReplicas + 1;

        ShardRoutingState[] replicaStates = new ShardRoutingState[assignedReplicas + unassignedReplicas];
        for (int i = 0; i < assignedReplicas; i++) {
            replicaStates[i] = randomFrom(ShardRoutingState.STARTED, ShardRoutingState.RELOCATING);
        }
        for (int i = assignedReplicas; i < replicaStates.length; i++) {
            replicaStates[i] = ShardRoutingState.UNASSIGNED;
        }

        final ClusterState state = state(index, true, ShardRoutingState.STARTED, replicaStates);
        logger.debug("using active shard count of [{}], assigned shards [{}], total shards [{}]." +
                     " expecting op to [{}]. using state: \n{}",
                     request.waitForActiveShards(), 1 + assignedReplicas, 1 + assignedReplicas + unassignedReplicas,
                     passesActiveShardCheck ? "succeed" : "retry", state);
        final long primaryTerm = state.metadata().index(index).primaryTerm(shardId.id());
        final IndexShardRoutingTable shardRoutingTable = state.routingTable().index(index).shard(shardId.id());

        final Set<String> inSyncAllocationIds = state.metadata().index(index).inSyncAllocationIds(0);
        Set<String> trackedShards = new HashSet<>();
        addTrackingInfo(shardRoutingTable, null, trackedShards, new HashSet<>());
        final ReplicationGroup initialReplicationGroup = new ReplicationGroup(shardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final ShardRouting primaryShard = shardRoutingTable.primaryShard();
        final TestReplicationOperation op = new TestReplicationOperation(
            request,
            new TestPrimary(primaryShard, () -> initialReplicationGroup, threadPool),
            listener,
            new TestReplicaProxy(),
            logger,
            threadPool,
            "test",
            primaryTerm);

        if (passesActiveShardCheck) {
            assertThat(op.checkActiveShardCount()).isNull();
            op.execute();
            assertThat(request.processedOnPrimary.get()).as("operations should have been performed, active shard count is met").isTrue();
        } else {
            assertThat(op.checkActiveShardCount()).isNotNull();
            op.execute();
            assertThat(request.processedOnPrimary.get()).as("operations should not have been perform, active shard count is *NOT* met").isFalse();
            assertListenerThrows("should throw exception to trigger retry", listener, UnavailableShardsException.class);
        }
    }

    @Test
    public void testPrimaryFailureHandlingReplicaResponse() throws Exception {
        final String index = "test";
        final ShardId shardId = new ShardId(index, index, 0);

        final Request request = new Request(shardId);

        final ClusterState state = stateWithActivePrimary(index, true, 1, 0);
        final IndexMetadata indexMetadata = state.metadata().index(index);
        final long primaryTerm = indexMetadata.primaryTerm(0);
        final ShardRouting primaryRouting = state.routingTable().shardRoutingTable(shardId).primaryShard();

        final Set<String> inSyncAllocationIds = indexMetadata.inSyncAllocationIds(0);
        final IndexShardRoutingTable shardRoutingTable = state.routingTable().index(index).shard(shardId.id());
        final Set<String> trackedShards = shardRoutingTable.getAllAllocationIds();
        final ReplicationGroup initialReplicationGroup = new ReplicationGroup(shardRoutingTable, inSyncAllocationIds, trackedShards, 0);

        final boolean fatal = randomBoolean();
        final AtomicBoolean primaryFailed = new AtomicBoolean();
        final ReplicationOperation.Primary<Request, Request, TestPrimary.Result> primary =
            new TestPrimary(primaryRouting, () -> initialReplicationGroup, threadPool) {

                @Override
                public void failShard(String message, Exception exception) {
                    primaryFailed.set(true);
                }

                @Override
                public void updateLocalCheckpointForShard(String allocationId, long checkpoint) {
                    if (primaryRouting.allocationId().getId().equals(allocationId)) {
                        super.updateLocalCheckpointForShard(allocationId, checkpoint);
                    } else {
                        if (fatal) {
                            throw new NullPointerException();
                        } else {
                            throw new AlreadyClosedException("already closed");
                        }
                    }
                }

            };

        final PlainFuture<TestPrimary.Result> listener = new PlainFuture<>();
        final ReplicationOperation.Replicas<Request> replicas = new TestReplicaProxy(Collections.emptyMap());
        TestReplicationOperation operation = new TestReplicationOperation(
            request, primary, listener, replicas, logger, threadPool, "test", primaryTerm);
        operation.execute();

        assertThat(primaryFailed.get()).isEqualTo(fatal);
        final ShardInfo shardInfo = FutureUtils.get(listener).getShardInfo();
        assertThat(shardInfo.getFailed()).isEqualTo(0);
        assertThat(shardInfo.getFailures()).isEmpty();
        assertThat(shardInfo.getSuccessful()).isEqualTo(1 + getExpectedReplicas(shardId, state, trackedShards).size());
    }

    private Set<ShardRouting> getExpectedReplicas(ShardId shardId, ClusterState state, Set<String> trackedShards) {
        Set<ShardRouting> expectedReplicas = new HashSet<>();
        String localNodeId = state.nodes().getLocalNodeId();
        if (state.routingTable().hasIndex(shardId.getIndexUUID())) {
            for (ShardRouting shardRouting : state.routingTable().shardRoutingTable(shardId)) {
                if (shardRouting.unassigned()) {
                    continue;
                }
                if (localNodeId.equals(shardRouting.currentNodeId()) == false) {
                    if (trackedShards.contains(shardRouting.allocationId().getId())) {
                        expectedReplicas.add(shardRouting);
                    }
                }

                if (shardRouting.relocating() && localNodeId.equals(shardRouting.relocatingNodeId()) == false) {
                    if (trackedShards.contains(shardRouting.getTargetRelocatingShard().allocationId().getId())) {
                        expectedReplicas.add(shardRouting.getTargetRelocatingShard());
                    }
                }
            }
        }
        return expectedReplicas;
    }


    public static class Request extends ReplicationRequest<Request> {

        public AtomicBoolean processedOnPrimary = new AtomicBoolean();
        public AtomicBoolean runPostReplicationActionsOnPrimary = new AtomicBoolean();
        public Set<ShardRouting> processedOnReplicas = Sets.newConcurrentHashSet();

        Request(ShardId shardId) {
            super(shardId);
            this.waitForActiveShards = ActiveShardCount.NONE;
            // keep things simple
        }

        @Override
        public String toString() {
            return "Request{}";
        }
    }

    static class TestPrimary implements ReplicationOperation.Primary<Request, Request, TestPrimary.Result> {
        final ShardRouting routing;
        final long localCheckpoint;
        final long globalCheckpoint;
        final long maxSeqNoOfUpdatesOrDeletes;
        final Supplier<ReplicationGroup> replicationGroupSupplier;
        final PendingReplicationActions pendingReplicationActions;
        final Map<String, Long> knownLocalCheckpoints = new HashMap<>();
        final Map<String, Long> knownGlobalCheckpoints = new HashMap<>();

        TestPrimary(ShardRouting routing, Supplier<ReplicationGroup> replicationGroupSupplier, ThreadPool threadPool) {
            this.routing = routing;
            this.replicationGroupSupplier = replicationGroupSupplier;
            this.localCheckpoint = random().nextLong();
            this.globalCheckpoint = randomNonNegativeLong();
            this.maxSeqNoOfUpdatesOrDeletes = randomNonNegativeLong();
            this.pendingReplicationActions = new PendingReplicationActions(routing.shardId(), threadPool);
        }

        @Override
        public ShardRouting routingEntry() {
            return routing;
        }

        @Override
        public void failShard(String message, Exception exception) {
            throw new AssertionError("should shouldn't be failed with [" + message + "]", exception);
        }

        @Override
        public void perform(Request request, ActionListener<Result> listener) {
            if (request.processedOnPrimary.compareAndSet(false, true) == false) {
                fail("processed [" + request + "] twice");
            }
            listener.onResponse(new Result(request));
        }

        static class Result implements ReplicationOperation.PrimaryResult<Request> {
            private final Request request;
            private ShardInfo shardInfo;

            Result(Request request) {
                this.request = request;
            }

            @Override
            public Request replicaRequest() {
                return request;
            }

            @Override
            public void setShardInfo(ShardInfo shardInfo) {
                this.shardInfo = shardInfo;
            }


            @Override
            public void runPostReplicationActions(ActionListener<Void> listener) {
                if (request.runPostReplicationActionsOnPrimary.compareAndSet(false, true) == false) {
                    fail("processed [" + request + "] twice");
                }
                listener.onResponse(null);
            }

            public ShardInfo getShardInfo() {
                return shardInfo;
            }
        }

        @Override
        public void updateLocalCheckpointForShard(String allocationId, long checkpoint) {
            knownLocalCheckpoints.put(allocationId, checkpoint);
        }

        @Override
        public void updateGlobalCheckpointForShard(String allocationId, long globalCheckpoint) {
            knownGlobalCheckpoints.put(allocationId, globalCheckpoint);
        }

        @Override
        public long localCheckpoint() {
            return localCheckpoint;
        }

        @Override
        public long globalCheckpoint() {
            return globalCheckpoint;
        }

        @Override
        public long computedGlobalCheckpoint() {
            return globalCheckpoint;
        }

        @Override
        public long maxSeqNoOfUpdatesOrDeletes() {
            return maxSeqNoOfUpdatesOrDeletes;
        }

        @Override
        public ReplicationGroup getReplicationGroup() {
            return replicationGroupSupplier.get();
        }

        @Override
        public PendingReplicationActions getPendingReplicationActions() {
            pendingReplicationActions.accept(getReplicationGroup());
            return pendingReplicationActions;
        }
    }

    static class ReplicaResponse implements ReplicationOperation.ReplicaResponse {
        final long localCheckpoint;
        final long globalCheckpoint;

        ReplicaResponse(long localCheckpoint, long globalCheckpoint) {
            this.localCheckpoint = localCheckpoint;
            this.globalCheckpoint = globalCheckpoint;
        }

        @Override
        public long localCheckpoint() {
            return localCheckpoint;
        }

        @Override
        public long globalCheckpoint() {
            return globalCheckpoint;
        }

    }

    static class TestReplicaProxy implements ReplicationOperation.Replicas<Request> {

        private final int attemptsBeforeSuccess;
        private final AtomicInteger attemptsNumber = new AtomicInteger(0);
        final Map<ShardRouting, Exception> opFailures;
        private final boolean retryable;

        final Set<ShardRouting> failedReplicas = Sets.newConcurrentHashSet();

        final Map<String, Long> generatedLocalCheckpoints = new ConcurrentHashMap<>();

        final Map<String, Long> generatedGlobalCheckpoints = new ConcurrentHashMap<>();

        final Set<String> markedAsStaleCopies = Sets.newConcurrentHashSet();

        TestReplicaProxy() {
            this(Collections.emptyMap());
        }

        TestReplicaProxy(Map<ShardRouting, Exception> opFailures) {
            this(opFailures, false);
        }

        TestReplicaProxy(Map<ShardRouting, Exception> opFailures, boolean retryable) {
            this.opFailures = opFailures;
            this.retryable = retryable;
            if (retryable) {
                attemptsBeforeSuccess = randomInt(2) + 1;
            } else {
                attemptsBeforeSuccess = Integer.MAX_VALUE;
            }
        }

        @Override
        public void performOn(
                final ShardRouting replica,
                final Request request,
                final long primaryTerm,
                final long globalCheckpoint,
                final long maxSeqNoOfUpdatesOrDeletes,
                final ActionListener<ReplicationOperation.ReplicaResponse> listener) {
            boolean added = request.processedOnReplicas.add(replica);
            if (retryable == false) {
                assertThat(added).as("replica request processed twice on [" + replica + "]").isTrue();
            }
            // If replication is not retryable OR this is the first attempt, the post replication actions
            // should not have run.
            if (retryable == false || added) {
                assertThat(request.runPostReplicationActionsOnPrimary.get()).as("primary post replication actions should run after replication").isFalse();
            }
            // If this is a retryable scenario and this is the second try, we finish successfully
            int n = attemptsNumber.incrementAndGet();
            if (opFailures.containsKey(replica) && n <= attemptsBeforeSuccess) {
                listener.onFailure(opFailures.get(replica));
            } else {
                final long generatedLocalCheckpoint = random().nextLong();
                final long generatedGlobalCheckpoint = random().nextLong();
                final String allocationId = replica.allocationId().getId();
                assertThat(generatedLocalCheckpoints.put(allocationId, generatedLocalCheckpoint)).isNull();
                assertThat(generatedGlobalCheckpoints.put(allocationId, generatedGlobalCheckpoint)).isNull();
                listener.onResponse(new ReplicaResponse(generatedLocalCheckpoint, generatedGlobalCheckpoint));
            }
        }

        @Override
        public void failShardIfNeeded(ShardRouting replica,
                                      long primaryTerm,
                                      String message,
                                      Exception exception,
                                      ActionListener<Void> listener) {
            if (failedReplicas.add(replica) == false) {
                fail("replica [" + replica + "] was failed twice");
            }
            if (opFailures.containsKey(replica)) {
                listener.onResponse(null);
            } else {
                fail("replica [" + replica + "] was failed");
            }
        }

        @Override
        public void markShardCopyAsStaleIfNeeded(ShardId shardId,
                                                 String allocationId,
                                                 long primaryTerm,
                                                 ActionListener<Void> listener) {
            if (markedAsStaleCopies.add(allocationId) == false) {
                fail("replica [" + allocationId + "] was marked as stale twice");
            }
            listener.onResponse(null);
        }
    }

    class TestReplicationOperation extends ReplicationOperation<Request, Request, TestPrimary.Result> {

        TestReplicationOperation(Request request,
                                 Primary<Request, Request, TestPrimary.Result> primary,
                                 ActionListener<TestPrimary.Result> listener,
                                 Replicas<Request> replicas,
                                 long primaryTerm,
                                 TimeValue initialRetryBackoffBound,
                                 TimeValue retryTimeout) {
            this(request, primary, listener, replicas, ReplicationOperationTests.this.logger, threadPool, "test", primaryTerm,
                initialRetryBackoffBound, retryTimeout);
        }

        TestReplicationOperation(Request request, Primary<Request, Request, TestPrimary.Result> primary,
                                 ActionListener<TestPrimary.Result> listener,
                                 Replicas<Request> replicas, Logger logger, ThreadPool threadPool, String opType, long primaryTerm) {
            this(request, primary, listener, replicas, logger, threadPool, opType, primaryTerm, TimeValue.timeValueMillis(50),
                TimeValue.timeValueSeconds(1));
        }

        TestReplicationOperation(Request request, Primary<Request, Request, TestPrimary.Result> primary,
                                 ActionListener<TestPrimary.Result> listener,
                                 Replicas<Request> replicas, Logger logger, ThreadPool threadPool, String opType, long primaryTerm,
                                 TimeValue initialRetryBackoffBound, TimeValue retryTimeout) {
            super(request, primary, listener, replicas, logger, threadPool, opType, primaryTerm, initialRetryBackoffBound, retryTimeout);
        }
    }

    private <T> void assertListenerThrows(String msg, PlainFuture<T> listener, Class<?> klass) throws InterruptedException {
        try {
            listener.get();
            fail(msg);
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isExactlyInstanceOf(klass);
        }
    }

}

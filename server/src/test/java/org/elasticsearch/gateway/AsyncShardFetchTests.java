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

import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.Version;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.nodes.BaseNodeResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AsyncShardFetchTests extends ESTestCase {
    private final DiscoveryNode node1 = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Collections.emptyMap(),
            Collections.singleton(DiscoveryNodeRole.DATA_ROLE), Version.CURRENT);
    private final Response response1 = new Response(node1);
    private final Response response1_2 = new Response(node1);
    private final Throwable failure1 = new Throwable("simulated failure 1");
    private final DiscoveryNode node2 = new DiscoveryNode("node2", buildNewFakeTransportAddress(), Collections.emptyMap(),
            Collections.singleton(DiscoveryNodeRole.DATA_ROLE), Version.CURRENT);
    private final Response response2 = new Response(node2);
    private final Throwable failure2 = new Throwable("simulate failure 2");

    private ThreadPool threadPool;
    private TestFetch test;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        this.threadPool = new TestThreadPool(getTestName());
        this.test = new TestFetch(threadPool);
    }

    @After
    public void terminate() throws Exception {
        terminate(threadPool);
    }

    @Test
    public void testClose() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // first fetch, no data, still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire a response, wait on reroute incrementing
        test.fireSimulationAndWait(node1.getId());
        // verify we get back the data node
        assertThat(test.reroute.get()).isEqualTo(1);
        test.close();
        try {
            test.fetchData(nodes, emptySet());
            fail("fetch data should fail when closed");
        } catch (IllegalStateException _) {
            // all is well
        }
    }

    @Test
    public void testFullCircleSingleNodeSuccess() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // first fetch, no data, still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire a response, wait on reroute incrementing
        test.fireSimulationAndWait(node1.getId());
        // verify we get back the data node
        assertThat(test.reroute.get()).isEqualTo(1);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
    }

    @Test
    public void testFullCircleSingleNodeFailure() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        // add a failed response for node1
        test.addSimulation(node1.getId(), failure1);

        // first fetch, no data, still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire a response, wait on reroute incrementing
        test.fireSimulationAndWait(node1.getId());
        // failure, fetched data exists, but has no data
        assertThat(test.reroute.get()).isEqualTo(1);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(0);

        // on failure, we reset the failure on a successive call to fetchData, and try again afterwards
        test.addSimulation(node1.getId(), response1);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        test.fireSimulationAndWait(node1.getId());
        // 2 reroutes, cause we have a failure that we clear
        assertThat(test.reroute.get()).isEqualTo(3);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
    }

    @Test
    public void testIgnoreResponseFromDifferentRound() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // first fetch, no data, still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // handle a response with incorrect round id, wait on reroute incrementing
        test.processAsyncFetch(Collections.singletonList(response1), Collections.emptyList(), 0);
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(1);

        // fire a response (with correct round id), wait on reroute incrementing
        test.fireSimulationAndWait(node1.getId());
        // verify we get back the data node
        assertThat(test.reroute.get()).isEqualTo(2);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
    }

    @Test
    public void testIgnoreFailureFromDifferentRound() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        // add a failed response for node1
        test.addSimulation(node1.getId(), failure1);

        // first fetch, no data, still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // handle a failure with incorrect round id, wait on reroute incrementing
        test.processAsyncFetch(Collections.emptyList(), Collections.singletonList(
            new FailedNodeException(node1.getId(), "dummy failure", failure1)), 0);
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(1);

        // fire a response, wait on reroute incrementing
        test.fireSimulationAndWait(node1.getId());
        // failure, fetched data exists, but has no data
        assertThat(test.reroute.get()).isEqualTo(2);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(0);
    }

    @Test
    public void testTwoNodesOnSetup() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).add(node2).build();
        test.addSimulation(node1.getId(), response1);
        test.addSimulation(node2.getId(), response2);

        // no fetched data, 2 requests still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire the first response, it should trigger a reroute
        test.fireSimulationAndWait(node1.getId());
        // there is still another on going request, so no data
        assertThat(test.getNumberOfInFlightFetches()).isEqualTo(1);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        // fire the second simulation, this should allow us to get the data
        test.fireSimulationAndWait(node2.getId());
        // no more ongoing requests, we should fetch the data
        assertThat(test.reroute.get()).isEqualTo(2);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(2);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
        assertThat(fetchData.getData().get(node2)).isSameAs(response2);
    }

    @Test
    public void testTwoNodesOnSetupAndFailure() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).add(node2).build();
        test.addSimulation(node1.getId(), response1);
        test.addSimulation(node2.getId(), failure2);

        // no fetched data, 2 requests still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire the first response, it should trigger a reroute
        test.fireSimulationAndWait(node1.getId());
        assertThat(test.reroute.get()).isEqualTo(1);
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        // fire the second simulation, this should allow us to get the data
        test.fireSimulationAndWait(node2.getId());
        assertThat(test.reroute.get()).isEqualTo(2);
        // since one of those failed, we should only have one entry
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
    }

    @Test
    public void testTwoNodesAddedInBetween() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // no fetched data, 2 requests still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // fire the first response, it should trigger a reroute
        test.fireSimulationAndWait(node1.getId());

        // now, add a second node to the nodes, it should add it to the ongoing requests
        nodes = DiscoveryNodes.builder(nodes).add(node2).build();
        test.addSimulation(node2.getId(), response2);
        // no fetch data, has a new node introduced
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        // fire the second simulation, this should allow us to get the data
        test.fireSimulationAndWait(node2.getId());

        // since one of those failed, we should only have one entry
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(2);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);
        assertThat(fetchData.getData().get(node2)).isSameAs(response2);
    }

    @Test
    public void testClearCache() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // must work also with no data
        test.clearCacheForNode(node1.getId());

        // no fetched data, request still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        test.fireSimulationAndWait(node1.getId());
        assertThat(test.reroute.get()).isEqualTo(1);

        // verify we get back right data from node
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);

        // second fetch gets same data
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1);

        test.clearCacheForNode(node1.getId());

        // prepare next request
        test.addSimulation(node1.getId(), response1_2);

        // no fetched data, new request on going
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        test.fireSimulationAndWait(node1.getId());
        assertThat(test.reroute.get()).isEqualTo(2);

        // verify we get new data back
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1_2);
    }

    @Test
    public void testConcurrentRequestAndClearCache() throws Exception {
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(node1).build();
        test.addSimulation(node1.getId(), response1);

        // no fetched data, request still on going
        AsyncShardFetch.FetchResult<Response> fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();
        assertThat(test.reroute.get()).isEqualTo(0);

        // clear cache while request is still on going, before it is processed
        test.clearCacheForNode(node1.getId());

        test.fireSimulationAndWait(node1.getId());
        assertThat(test.reroute.get()).isEqualTo(1);

        // prepare next request
        test.addSimulation(node1.getId(), response1_2);

        // verify still no fetched data, request still on going
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isFalse();

        test.fireSimulationAndWait(node1.getId());
        assertThat(test.reroute.get()).isEqualTo(2);

        // verify we get new data back
        fetchData = test.fetchData(nodes, emptySet());
        assertThat(fetchData.hasData()).isTrue();
        assertThat(fetchData.getData()).hasSize(1);
        assertThat(fetchData.getData().get(node1)).isSameAs(response1_2);

    }

    static class TestFetch extends AsyncShardFetch<Response> {

        static class Entry {
            public final Response response;
            public final Throwable failure;
            private final CountDownLatch executeLatch = new CountDownLatch(1);
            private final CountDownLatch waitLatch = new CountDownLatch(1);

            Entry(Response response, Throwable failure) {
                this.response = response;
                this.failure = failure;
            }
        }

        private final ThreadPool threadPool;
        private final Map<String, Entry> simulations = new ConcurrentHashMap<>();
        private final AtomicInteger reroute = new AtomicInteger();

        TestFetch(ThreadPool threadPool) {
            super(LogManager.getLogger(TestFetch.class), "test", new ShardId("test", "_na_", 1), "", null);
            this.threadPool = threadPool;
        }

        public void addSimulation(String nodeId, Response response) {
            simulations.put(nodeId, new Entry(response, null));
        }

        public void addSimulation(String nodeId, Throwable t) {
            simulations.put(nodeId, new Entry(null, t));
        }

        public void fireSimulationAndWait(String nodeId) throws InterruptedException {
            simulations.get(nodeId).executeLatch.countDown();
            simulations.get(nodeId).waitLatch.await();
            simulations.remove(nodeId);
        }

        @Override
        protected void reroute(ShardId shardId, String reason) {
            reroute.incrementAndGet();
        }

        @Override
        protected void asyncFetch(DiscoveryNode[] nodes, long fetchingRound) {
            for (final DiscoveryNode node : nodes) {
                final String nodeId = node.getId();
                threadPool.generic().execute(new Runnable() {
                    @Override
                    public void run() {
                        Entry entry = null;
                        try {
                            entry = simulations.get(nodeId);
                            if (entry == null) {
                                // we are simulating a master node switch, wait for it to not be null
                                assertBusy(() -> assertThat(simulations.containsKey(nodeId)).isTrue());
                            }
                            assert entry != null;
                            entry.executeLatch.await();
                            if (entry.failure != null) {
                                processAsyncFetch(null,
                                    Collections.singletonList(new FailedNodeException(nodeId, "unexpected", entry.failure)), fetchingRound);
                            } else {
                                processAsyncFetch(Collections.singletonList(entry.response), null, fetchingRound);
                            }
                        } catch (Exception e) {
                            logger.error("unexpected failure", e);
                        } finally {
                            if (entry != null) {
                                entry.waitLatch.countDown();
                            }
                        }
                    }
                });
            }
        }
    }

    static class Response extends BaseNodeResponse {
        Response(DiscoveryNode node) {
            super(node);
        }
    }
}

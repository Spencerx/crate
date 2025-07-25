/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.elasticsearch.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.crate.common.exceptions.Exceptions;
import io.crate.common.io.IOUtils;
import io.crate.netty.NettyBootstrap;

public class TransportActionProxyTests extends ESTestCase {
    protected ThreadPool threadPool;
    // we use always a non-alpha or beta version here otherwise minimumCompatibilityVersion will be different for the two used versions
    private static final Version CURRENT_VERSION = Version.fromId(Version.CURRENT.major * 1000000 + 99);
    protected static final Version version0 = CURRENT_VERSION.minimumCompatibilityVersion();

    protected DiscoveryNode nodeA;
    protected MockTransportService serviceA;

    protected static final Version version1 = Version.fromId(CURRENT_VERSION.major * 1000000 + 199);
    protected DiscoveryNode nodeB;
    protected MockTransportService serviceB;

    protected DiscoveryNode nodeC;
    protected MockTransportService serviceC;
    private NettyBootstrap nettyBootstrap;


    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        nettyBootstrap = new NettyBootstrap(Settings.EMPTY);
        nettyBootstrap.start();
        threadPool = new TestThreadPool(getClass().getName());
        serviceA = buildService(version0); // this one supports dynamic tracer updates
        nodeA = serviceA.getLocalDiscoNode();
        serviceB = buildService(version1); // this one doesn't support dynamic tracer updates
        nodeB = serviceB.getLocalDiscoNode();
        serviceC = buildService(version1); // this one doesn't support dynamic tracer updates
        nodeC = serviceC.getLocalDiscoNode();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        IOUtils.close(serviceA, serviceB, serviceC, nettyBootstrap, () -> terminate(threadPool));
    }

    private MockTransportService buildService(final Version version) {
        MockTransportService service = MockTransportService.createNewService(Settings.EMPTY, version, threadPool, nettyBootstrap);
        service.start();
        service.acceptIncomingRequests();
        return service;
    }

    @Test
    public void testSendMessage() throws InterruptedException {
        serviceA.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (request, channel) -> {
                                            assertThat(request.sourceNode).isEqualTo("TS_A");
                                            SimpleTestResponse response = new SimpleTestResponse("TS_A");
                                            channel.sendResponse(response);
                                        });
        TransportActionProxy.registerProxyAction(serviceA, "internal:test", SimpleTestResponse::new);
        AbstractSimpleTransportTestCase.connectToNode(serviceA, nodeB);

        serviceB.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (request, channel) -> {
                                            assertThat(request.sourceNode).isEqualTo("TS_A");
                                            SimpleTestResponse response = new SimpleTestResponse("TS_B");
                                            channel.sendResponse(response);
                                        });
        TransportActionProxy.registerProxyAction(serviceB, "internal:test", SimpleTestResponse::new);
        AbstractSimpleTransportTestCase.connectToNode(serviceB, nodeC);
        serviceC.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (request, channel) -> {
                                            assertThat(request.sourceNode).isEqualTo("TS_A");
                                            SimpleTestResponse response = new SimpleTestResponse("TS_C");
                                            channel.sendResponse(response);
                                        });
        TransportActionProxy.registerProxyAction(serviceC, "internal:test", SimpleTestResponse::new);

        CountDownLatch latch = new CountDownLatch(1);
        serviceA.sendRequest(nodeB, TransportActionProxy.getProxyAction("internal:test"), TransportActionProxy.wrapRequest(nodeC,
                                                                                                                           new SimpleTestRequest("TS_A")), new TransportResponseHandler<SimpleTestResponse>() {
            @Override
            public SimpleTestResponse read(StreamInput in) throws IOException {
                return new SimpleTestResponse(in);
            }

            @Override
            public void handleResponse(SimpleTestResponse response) {
                try {
                    assertThat(response.targetNode).isEqualTo("TS_C");
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void handleException(TransportException exp) {
                try {
                    throw new AssertionError(exp);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        });
        latch.await();
    }

    @Test
    public void testException() throws InterruptedException {
        serviceA.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (request, channel) -> {
                                            assertThat(request.sourceNode).isEqualTo("TS_A");
                                            SimpleTestResponse response = new SimpleTestResponse("TS_A");
                                            channel.sendResponse(response);
                                        });
        TransportActionProxy.registerProxyAction(serviceA, "internal:test", SimpleTestResponse::new);
        AbstractSimpleTransportTestCase.connectToNode(serviceA, nodeB);

        serviceB.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (request, channel) -> {
                                            assertThat(request.sourceNode).isEqualTo("TS_A");
                                            SimpleTestResponse response = new SimpleTestResponse("TS_B");
                                            channel.sendResponse(response);
                                        });
        TransportActionProxy.registerProxyAction(serviceB, "internal:test", SimpleTestResponse::new);
        AbstractSimpleTransportTestCase.connectToNode(serviceB, nodeC);
        serviceC.registerRequestHandler("internal:test", ThreadPool.Names.SAME, SimpleTestRequest::new,
                                        (_, _) -> {
                                            throw new ElasticsearchException("greetings from TS_C");
                                        });
        TransportActionProxy.registerProxyAction(serviceC, "internal:test", SimpleTestResponse::new);

        CountDownLatch latch = new CountDownLatch(1);
        serviceA.sendRequest(nodeB, TransportActionProxy.getProxyAction("internal:test"), TransportActionProxy.wrapRequest(nodeC,
                                                                                                                           new SimpleTestRequest("TS_A")), new TransportResponseHandler<SimpleTestResponse>() {
            @Override
            public SimpleTestResponse read(StreamInput in) throws IOException {
                return new SimpleTestResponse(in);
            }

            @Override
            public void handleResponse(SimpleTestResponse response) {
                try {
                    fail("expected exception");
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void handleException(TransportException exp) {
                try {
                    Throwable cause = Exceptions.firstCause(exp);
                    assertThat(cause.getMessage()).isEqualTo("greetings from TS_C");
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        });
        latch.await();
    }

    public static class SimpleTestRequest extends TransportRequest {
        String sourceNode;

        public SimpleTestRequest(String sourceNode) {
            this.sourceNode = sourceNode;
        }
        public SimpleTestRequest() {}

        public SimpleTestRequest(StreamInput in) throws IOException {
            super(in);
            sourceNode = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(sourceNode);
        }
    }

    public static class SimpleTestResponse extends TransportResponse {
        final String targetNode;

        SimpleTestResponse(String targetNode) {
            this.targetNode = targetNode;
        }

        SimpleTestResponse(StreamInput in) throws IOException {
            this.targetNode = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(targetNode);
        }
    }

    @Test
    public void testGetAction() {
        String action = "foo/bar";
        String proxyAction = TransportActionProxy.getProxyAction(action);
        assertThat(proxyAction.endsWith(action)).isTrue();
        assertThat(proxyAction).isEqualTo("internal:transport/proxy/foo/bar");
    }

    @Test
    public void testUnwrap() {
        TransportRequest transportRequest = TransportActionProxy.wrapRequest(nodeA, TransportService.HandshakeRequest.INSTANCE);
        assertThat(transportRequest instanceof TransportActionProxy.ProxyRequest).isTrue();
        assertThat(TransportActionProxy.unwrapRequest(transportRequest)).isSameAs(TransportService.HandshakeRequest.INSTANCE);
    }

    @Test
    public void testIsProxyAction() {
        String action = "foo/bar";
        String proxyAction = TransportActionProxy.getProxyAction(action);
        assertThat(TransportActionProxy.isProxyAction(proxyAction)).isTrue();
        assertThat(TransportActionProxy.isProxyAction(action)).isFalse();
    }

    @Test
    public void testIsProxyRequest() {
        assertThat(TransportActionProxy.isProxyRequest(new TransportActionProxy.ProxyRequest<>(TransportRequest.Empty.INSTANCE, null))).isTrue();
        assertThat(TransportActionProxy.isProxyRequest(TransportRequest.Empty.INSTANCE)).isFalse();
    }
}

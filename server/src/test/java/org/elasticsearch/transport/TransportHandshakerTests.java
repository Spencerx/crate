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

package org.elasticsearch.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.network.CloseableChannel;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.crate.common.unit.TimeValue;
import io.crate.concurrent.FutureActionListener;
import io.netty.channel.embedded.EmbeddedChannel;

public class TransportHandshakerTests extends ESTestCase {

    private TransportHandshaker handshaker;
    private DiscoveryNode node;
    private CloseableChannel channel;
    private TestThreadPool threadPool;
    private TransportHandshaker.HandshakeRequestSender requestSender;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        String nodeId = "node-id";
        channel = new CloseableChannel(new EmbeddedChannel(), false);
        requestSender = mock(TransportHandshaker.HandshakeRequestSender.class);
        node = new DiscoveryNode(nodeId, nodeId, nodeId, "host", "host_address", buildNewFakeTransportAddress(), Collections.emptyMap(),
            Collections.emptySet(), Version.CURRENT);
        threadPool = new TestThreadPool("thread-poll");
        handshaker = new TransportHandshaker(Version.CURRENT, threadPool, requestSender);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        threadPool.shutdown();
        super.tearDown();
    }

    @Test
    public void testHandshakeRequestAndResponse() throws Exception {
        FutureActionListener<Version> versionFuture = new FutureActionListener<>();
        long reqId = randomLongBetween(1, 10);
        handshaker.sendHandshake(reqId, node, channel, new TimeValue(30, TimeUnit.SECONDS), versionFuture);

        verify(requestSender).sendRequest(node, channel, reqId, Version.CURRENT);

        assertThat(versionFuture.isDone()).isFalse();

        TransportHandshaker.HandshakeRequest handshakeRequest = new TransportHandshaker.HandshakeRequest(Version.CURRENT);
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        handshakeRequest.writeTo(bytesStreamOutput);
        StreamInput input = bytesStreamOutput.bytes().streamInput();
        final FutureActionListener<TransportResponse> responseFuture = new FutureActionListener<>();
        final TestTransportChannel channel = new TestTransportChannel(responseFuture);
        handshaker.handleHandshake(channel, reqId, input);

        TransportResponseHandler<TransportHandshaker.HandshakeResponse> handler = handshaker.removeHandlerForHandshake(reqId);
        handler.handleResponse((TransportHandshaker.HandshakeResponse) responseFuture.get());

        assertThat(versionFuture.isDone()).isTrue();
        assertThat(versionFuture.get()).isEqualTo(Version.CURRENT);
    }

    @Test
    public void test_handshake_request_includes_minimumCompatibilityVersion() throws Exception {
        TransportHandshaker.HandshakeRequest handshakeRequest = new TransportHandshaker.HandshakeRequest(Version.CURRENT);
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        handshakeRequest.writeTo(bytesStreamOutput);
        StreamInput streamInput = bytesStreamOutput.bytes().streamInput();
        TaskId.readFromStream(streamInput);

        try (StreamInput messageInput = streamInput.readBytesReference().streamInput()) {
            Version minimumCompatibilityVersion = Version.fromId(messageInput.readVInt());
            assertThat(minimumCompatibilityVersion).isEqualTo(Version.CURRENT.minimumCompatibilityVersion());
        }
    }

    @Test
    public void testHandshakeRequestFutureVersionsCompatibility() throws Exception {
        long reqId = randomLongBetween(1, 10);
        handshaker.sendHandshake(reqId, node, channel, new TimeValue(30, TimeUnit.SECONDS), new FutureActionListener<>());

        verify(requestSender).sendRequest(node, channel, reqId, Version.CURRENT);

        TransportHandshaker.HandshakeRequest handshakeRequest = new TransportHandshaker.HandshakeRequest(Version.CURRENT);
        BytesStreamOutput currentHandshakeBytes = new BytesStreamOutput();
        handshakeRequest.writeTo(currentHandshakeBytes);

        BytesStreamOutput lengthCheckingHandshake = new BytesStreamOutput();
        BytesStreamOutput futureHandshake = new BytesStreamOutput();
        TaskId.EMPTY_TASK_ID.writeTo(lengthCheckingHandshake);
        TaskId.EMPTY_TASK_ID.writeTo(futureHandshake);
        try (BytesStreamOutput internalMessage = new BytesStreamOutput()) {
            Version.writeVersion(Version.CURRENT.minimumCompatibilityVersion(), internalMessage);
            lengthCheckingHandshake.writeBytesReference(internalMessage.bytes());
            internalMessage.write(new byte[1024]);
            futureHandshake.writeBytesReference(internalMessage.bytes());
        }
        StreamInput futureHandshakeStream = futureHandshake.bytes().streamInput();
        // We check that the handshake we serialize for this test equals the actual request.
        // Otherwise, we need to update the test.
        assertThat(lengthCheckingHandshake.bytes().length()).isEqualTo(currentHandshakeBytes.bytes().length());
        assertThat(futureHandshakeStream.available()).isEqualTo(1031);
        final FutureActionListener<TransportResponse> responseFuture = new FutureActionListener<>();
        final TestTransportChannel channel = new TestTransportChannel(responseFuture);
        handshaker.handleHandshake(channel, reqId, futureHandshakeStream);
        assertThat(futureHandshakeStream.available()).isEqualTo(0);

        TransportHandshaker.HandshakeResponse response = (TransportHandshaker.HandshakeResponse) responseFuture.get();

        assertThat(response.getResponseVersion()).isEqualTo(Version.CURRENT);
    }

    @Test
    public void testHandshakeError() throws IOException {
        FutureActionListener<Version> versionFuture = new FutureActionListener<>();
        long reqId = randomLongBetween(1, 10);
        handshaker.sendHandshake(reqId, node, channel, new TimeValue(30, TimeUnit.SECONDS), versionFuture);

        verify(requestSender).sendRequest(node, channel, reqId, Version.CURRENT);

        assertThat(versionFuture.isDone()).isFalse();

        TransportResponseHandler<TransportHandshaker.HandshakeResponse> handler = handshaker.removeHandlerForHandshake(reqId);
        handler.handleException(new TransportException("failed"));

        assertThat(versionFuture.isDone()).isTrue();
        assertThatThrownBy(versionFuture::get)
            .cause()
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageContaining("handshake failed");
    }

    @Test
    public void testSendRequestThrowsException() throws IOException {
        FutureActionListener<Version> versionFuture = new FutureActionListener<>();
        long reqId = randomLongBetween(1, 10);
        doThrow(new IOException("boom")).when(requestSender).sendRequest(node, channel, reqId, Version.CURRENT);

        handshaker.sendHandshake(reqId, node, channel, new TimeValue(30, TimeUnit.SECONDS), versionFuture);

        assertThat(versionFuture.isDone()).isTrue();
        assertThatThrownBy(versionFuture::get)
            .cause()
            .isExactlyInstanceOf(ConnectTransportException.class)
            .hasMessageContaining("failure to send internal:tcp/handshake");
        assertThat(handshaker.removeHandlerForHandshake(reqId)).isNull();
    }

    @Test
    public void testHandshakeTimeout() throws IOException {
        FutureActionListener<Version> versionFuture = new FutureActionListener<>();
        long reqId = randomLongBetween(1, 10);
        handshaker.sendHandshake(reqId, node, channel, new TimeValue(100, TimeUnit.MILLISECONDS), versionFuture);

        verify(requestSender).sendRequest(node, channel, reqId, Version.CURRENT);

        assertThatThrownBy(versionFuture::get)
            .cause()
            .isExactlyInstanceOf(ConnectTransportException.class)
            .hasMessageContaining("handshake_timeout");

        assertThat(handshaker.removeHandlerForHandshake(reqId)).isNull();
    }
}

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
package org.elasticsearch.cluster.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elasticsearch.monitor.StatusInfo.Status.HEALTHY;
import static org.elasticsearch.monitor.StatusInfo.Status.UNHEALTHY;
import static org.elasticsearch.node.Node.NODE_NAME_SETTING;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.support.PlainFuture;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.monitor.StatusInfo;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.CapturingTransport;
import org.elasticsearch.test.transport.CapturingTransport.CapturedRequest;
import org.elasticsearch.test.transport.MockTransport;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;
import org.junit.Test;

public class JoinHelperTests extends ESTestCase {

    public void testJoinDeduplication() {
        DeterministicTaskQueue deterministicTaskQueue = new DeterministicTaskQueue(
            Settings.builder().put(NODE_NAME_SETTING.getKey(), "node0").build(), random());
        CapturingTransport capturingTransport = new CapturingTransport();
        DiscoveryNode localNode = new DiscoveryNode("node0", buildNewFakeTransportAddress(), Version.CURRENT);
        TransportService transportService = capturingTransport.createTransportService(
            Settings.EMPTY,
            deterministicTaskQueue.getThreadPool(),
            x -> localNode,
            null
        );
        JoinHelper joinHelper = new JoinHelper(Settings.EMPTY, null, null, transportService, () -> 0L, () -> null,
            (joinRequest, joinCallback) -> { throw new AssertionError(); }, startJoinRequest -> { throw new AssertionError(); },
            Collections.emptyList(), (s, p, r) -> {},
            () -> new StatusInfo(HEALTHY, "info"));
        transportService.start();

        DiscoveryNode node1 = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode node2 = new DiscoveryNode("node2", buildNewFakeTransportAddress(), Version.CURRENT);

        assertThat(joinHelper.isJoinPending()).isFalse();

        // check that sending a join to node1 works
        Optional<Join> optionalJoin1 = randomBoolean() ? Optional.empty() :
            Optional.of(new Join(localNode, node1, randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong()));
        joinHelper.sendJoinRequest(node1, 0L, optionalJoin1);
        CapturedRequest[] capturedRequests1 = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests1.length).isEqualTo(1);
        CapturedRequest capturedRequest1 = capturedRequests1[0];
        assertThat(capturedRequest1.node).isEqualTo(node1);

        assertThat(joinHelper.isJoinPending()).isTrue();

        // check that sending a join to node2 works
        Optional<Join> optionalJoin2 = randomBoolean() ? Optional.empty() :
            Optional.of(new Join(localNode, node2, randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong()));
        joinHelper.sendJoinRequest(node2, 0L, optionalJoin2);
        CapturedRequest[] capturedRequests2 = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests2.length).isEqualTo(1);
        CapturedRequest capturedRequest2 = capturedRequests2[0];
        assertThat(capturedRequest2.node).isEqualTo(node2);

        // check that sending another join to node1 is a noop as the previous join is still in progress
        joinHelper.sendJoinRequest(node1, 0L, optionalJoin1);
        assertThat(capturingTransport.getCapturedRequestsAndClear().length).isEqualTo(0);

        // complete the previous join to node1
        if (randomBoolean()) {
            capturingTransport.handleResponse(capturedRequest1.requestId, TransportResponse.Empty.INSTANCE);
        } else {
            capturingTransport.handleRemoteError(capturedRequest1.requestId, new CoordinationStateRejectedException("dummy"));
        }

        // check that sending another join to node1 now works again
        joinHelper.sendJoinRequest(node1, 0L, optionalJoin1);
        CapturedRequest[] capturedRequests1a = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests1a.length).isEqualTo(1);
        CapturedRequest capturedRequest1a = capturedRequests1a[0];
        assertThat(capturedRequest1a.node).isEqualTo(node1);

        // check that sending another join to node2 works if the optionalJoin is different
        Optional<Join> optionalJoin2a = optionalJoin2.isPresent() && randomBoolean() ? Optional.empty() :
            Optional.of(new Join(localNode, node2, randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong()));
        joinHelper.sendJoinRequest(node2, 0L, optionalJoin2a);
        CapturedRequest[] capturedRequests2a = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests2a.length).isEqualTo(1);
        CapturedRequest capturedRequest2a = capturedRequests2a[0];
        assertThat(capturedRequest2a.node).isEqualTo(node2);

        // complete all the joins and check that isJoinPending is updated
        assertThat(joinHelper.isJoinPending()).isTrue();
        capturingTransport.handleRemoteError(capturedRequest2.requestId, new CoordinationStateRejectedException("dummy"));
        capturingTransport.handleRemoteError(capturedRequest1a.requestId, new CoordinationStateRejectedException("dummy"));
        capturingTransport.handleRemoteError(capturedRequest2a.requestId, new CoordinationStateRejectedException("dummy"));
        assertThat(joinHelper.isJoinPending()).isFalse();
    }

    public void testFailedJoinAttemptLogLevel() {
        assertThat(JoinHelper.FailedJoinAttempt.getLogLevel(new TransportException("generic transport exception"))).isEqualTo(Level.INFO);

        assertThat(JoinHelper.FailedJoinAttempt.getLogLevel(
                new RemoteTransportException("remote transport exception with generic cause", new Exception()))).isEqualTo(Level.INFO);

        assertThat(JoinHelper.FailedJoinAttempt.getLogLevel(
                new RemoteTransportException("caused by CoordinationStateRejectedException",
                        new CoordinationStateRejectedException("test")))).isEqualTo(Level.DEBUG);

        assertThat(JoinHelper.FailedJoinAttempt.getLogLevel(
                new RemoteTransportException("caused by FailedToCommitClusterStateException",
                        new FailedToCommitClusterStateException("test")))).isEqualTo(Level.DEBUG);

        assertThat(JoinHelper.FailedJoinAttempt.getLogLevel(
                new RemoteTransportException("caused by NotMasterException",
                        new NotMasterException("test")))).isEqualTo(Level.DEBUG);
    }

    @Test
    public void testJoinValidationRejectsMismatchedClusterUUID() {
        assertJoinValidationRejectsMismatchedClusterUUID(JoinHelper.VALIDATE_JOIN_ACTION_NAME,
            "join validation on cluster state with a different cluster uuid");
    }

    private void assertJoinValidationRejectsMismatchedClusterUUID(String actionName, String expectedMessage) {
        DeterministicTaskQueue deterministicTaskQueue = new DeterministicTaskQueue(
            Settings.builder().put(NODE_NAME_SETTING.getKey(), "node0").build(), random());
        MockTransport mockTransport = new MockTransport();
        DiscoveryNode localNode = new DiscoveryNode("node0", buildNewFakeTransportAddress(), Version.CURRENT);

        final ClusterState localClusterState = ClusterState.builder(ClusterName.DEFAULT).metadata(Metadata.builder()
            .generateClusterUuidIfNeeded().clusterUUIDCommitted(true)).build();

        TransportService transportService = mockTransport.createTransportService(Settings.EMPTY,
            deterministicTaskQueue.getThreadPool(), x -> localNode, null);
        new JoinHelper(Settings.EMPTY, null, null, transportService, () -> 0L, () -> localClusterState,
            (joinRequest, joinCallback) -> { throw new AssertionError(); }, startJoinRequest -> { throw new AssertionError(); },
            Collections.emptyList(), (s, p, r) -> {}, null); // registers request handler
        transportService.start();
        transportService.acceptIncomingRequests();

        final ClusterState otherClusterState = ClusterState.builder(ClusterName.DEFAULT).metadata(Metadata.builder()
            .generateClusterUuidIfNeeded()).build();

        final PlainFuture<TransportResponse.Empty> future = new PlainFuture<>();
        transportService.sendRequest(localNode, actionName,
            new ValidateJoinRequest(otherClusterState),
            new ActionListenerResponseHandler<>(actionName, future, in -> TransportResponse.Empty.INSTANCE));
        deterministicTaskQueue.runAllTasks();

        assertThatThrownBy(future::get)
            .rootCause()
            .isExactlyInstanceOf(CoordinationStateRejectedException.class)
            .hasMessageContainingAll(
                expectedMessage,
                localClusterState.metadata().clusterUUID(),
                otherClusterState.metadata().clusterUUID()
            );
    }

    public void testJoinFailureOnUnhealthyNodes() {
        DeterministicTaskQueue deterministicTaskQueue = new DeterministicTaskQueue(
            Settings.builder().put(NODE_NAME_SETTING.getKey(), "node0").build(), random());
        CapturingTransport capturingTransport = new CapturingTransport();
        DiscoveryNode localNode = new DiscoveryNode("node0", buildNewFakeTransportAddress(), Version.CURRENT);
        TransportService transportService = capturingTransport.createTransportService(Settings.EMPTY,
            deterministicTaskQueue.getThreadPool(), x -> localNode, null);
        AtomicReference<StatusInfo> nodeHealthServiceStatus = new AtomicReference<>
            (new StatusInfo(UNHEALTHY, "unhealthy-info"));
        JoinHelper joinHelper = new JoinHelper(Settings.EMPTY, null, null, transportService, () -> 0L, () -> null,
            (joinRequest, joinCallback) -> { throw new AssertionError(); }, startJoinRequest -> { throw new AssertionError(); },
            Collections.emptyList(), (s, p, r) -> {}, () -> nodeHealthServiceStatus.get());
        transportService.start();

        DiscoveryNode node1 = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode node2 = new DiscoveryNode("node2", buildNewFakeTransportAddress(), Version.CURRENT);

        assertThat(joinHelper.isJoinPending()).isFalse();

        // check that sending a join to node1 doesn't work
        Optional<Join> optionalJoin1 = randomBoolean() ? Optional.empty() :
            Optional.of(new Join(localNode, node1, randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong()));
        joinHelper.sendJoinRequest(node1, randomNonNegativeLong(), optionalJoin1);
        CapturedRequest[] capturedRequests1 = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests1.length).isEqualTo(0);

        assertThat(joinHelper.isJoinPending()).isFalse();

        // check that sending a join to node2 doesn't work
        Optional<Join> optionalJoin2 = randomBoolean() ? Optional.empty() :
            Optional.of(new Join(localNode, node2, randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong()));

        transportService.start();
        joinHelper.sendJoinRequest(node2, randomNonNegativeLong(), optionalJoin2);

        CapturedRequest[] capturedRequests2 = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests2.length).isEqualTo(0);

        assertThat(joinHelper.isJoinPending()).isFalse();

        nodeHealthServiceStatus.getAndSet(new StatusInfo(HEALTHY, "healthy-info"));
        // check that sending another join to node1 now works again
        joinHelper.sendJoinRequest(node1, 0L, optionalJoin1);
        CapturedRequest[] capturedRequests1a = capturingTransport.getCapturedRequestsAndClear();
        assertThat(capturedRequests1a.length).isEqualTo(1);
        CapturedRequest capturedRequest1a = capturedRequests1a[0];
        assertThat(capturedRequest1a.node).isEqualTo(node1);
    }
}

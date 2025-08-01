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
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.network.CloseableChannel;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.junit.Test;

public class TcpTransportTest extends ESTestCase {

    /** Test ipv4 host with a default port works */
    @Test
    public void testParseV4DefaultPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("127.0.0.1", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("127.0.0.1");
        assertThat(addresses[0].getPort()).isEqualTo(1234);
    }

    /** Test ipv4 host with port works */
    @Test
    public void testParseV4WithPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("127.0.0.1:2345", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("127.0.0.1");
        assertThat(addresses[0].getPort()).isEqualTo(2345);
    }

    /** Test unbracketed ipv6 hosts in configuration fail. Leave no ambiguity */
    @Test
    public void testParseV6UnBracketed() throws Exception {
        try {
            TcpTransport.parse("::1", 1234);
            fail("should have gotten exception");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage().contains("must be bracketed")).isTrue();
        }
    }

    /** Test ipv6 host with a default port works */
    @Test
    public void testParseV6DefaultPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("[::1]", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("::1");
        assertThat(addresses[0].getPort()).isEqualTo(1234);
    }

    /** Test ipv6 host with port works */
    @Test
    public void testParseV6WithPort() throws Exception {
        TransportAddress[] addresses = TcpTransport.parse("[::1]:2345", 1234);
        assertThat(addresses.length).isEqualTo(1);

        assertThat(addresses[0].getAddress()).isEqualTo("::1");
        assertThat(addresses[0].getPort()).isEqualTo(2345);
    }

    @Test
    public void testRejectsPortRanges() {
        assertThatThrownBy(() -> TcpTransport.parse("[::1]:100-200", 1000))
            .isExactlyInstanceOf(NumberFormatException.class);
    }

    @Test
    public void testDefaultSeedAddressesWithDefaultPort() {
        testDefaultSeedAddresses(Settings.EMPTY, List.of(
            "[::1]:4300", "[::1]:4301", "[::1]:4302", "[::1]:4303", "[::1]:4304", "[::1]:4305",
            "127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302", "127.0.0.1:4303", "127.0.0.1:4304", "127.0.0.1:4305"));
    }

    @Test
    public void testDefaultSeedAddressesWithNonstandardGlobalPortRange() {
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4500-4600").build(), List.of(
            "[::1]:4500", "[::1]:4501", "[::1]:4502", "[::1]:4503", "[::1]:4504", "[::1]:4505",
            "127.0.0.1:4500", "127.0.0.1:4501", "127.0.0.1:4502", "127.0.0.1:4503", "127.0.0.1:4504", "127.0.0.1:4505"));
    }

    @Test
    public void testDefaultSeedAddressesWithSmallGlobalPortRange() {
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4300-4302").build(), List.of(
            "[::1]:4300", "[::1]:4301", "[::1]:4302", "127.0.0.1:4300", "127.0.0.1:4301", "127.0.0.1:4302"));
    }

    @Test
    public void testDefaultSeedAddressesWithNonstandardSinglePort() {
        testDefaultSeedAddresses(Settings.builder().put(TransportSettings.PORT.getKey(), "4500").build(), List.of(
            "[::1]:4500", "127.0.0.1:4500"));
    }

    @Test
    public void testTLSHeader() throws IOException {
        try (BytesStreamOutput streamOutput = new BytesStreamOutput(1 << 14)) {

            streamOutput.write(0x16);
            streamOutput.write(0x03);
            byte byte1 = randomByte();
            streamOutput.write(byte1);
            byte byte2 = randomByte();
            streamOutput.write(byte2);
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());
            streamOutput.write(randomByte());

            String expected = "SSL/TLS request received but SSL/TLS is not enabled on this node, got (16,3,"
                + Integer.toHexString(byte1 & 0xFF) + ","
                + Integer.toHexString(byte2 & 0xFF) + ")";
            assertThatThrownBy(() -> TcpTransport.readMessageLength(streamOutput.bytes()))
                .isExactlyInstanceOf(StreamCorruptedException.class)
                .hasMessage(expected);
        }
    }

    public void testDefaultSeedAddresses(final Settings settings, List<String> expectedAddresses) {
        final TestThreadPool testThreadPool = new TestThreadPool("test");
        try {
            final TcpTransport tcpTransport = new TcpTransport(settings,
                                                               Version.CURRENT,
                                                               testThreadPool,
                                                               new PageCacheRecycler(Settings.EMPTY),
                                                               new NoneCircuitBreakerService(),
                                                               writableRegistry(),
                                                               new NetworkService(Collections.emptyList())) {

                @Override
                protected CloseableChannel bind(InetSocketAddress address) {
                    throw new UnsupportedOperationException();
                }

                @Override
                protected ConnectResult initiateChannel(DiscoveryNode node) {
                    throw new UnsupportedOperationException();
                }

                @Override
                protected void stopInternal() {
                    throw new UnsupportedOperationException();
                }
            };

            assertThat(tcpTransport.getDefaultSeedAddresses()).containsExactlyInAnyOrder(expectedAddresses.toArray(new String[]{}));
        } finally {
            testThreadPool.shutdown();
        }
    }
}

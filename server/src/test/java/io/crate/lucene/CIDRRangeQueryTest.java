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

package io.crate.lucene;

import static io.crate.testing.Asserts.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.elasticsearch.Version;
import org.junit.Test;

import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.QueryTester;

public class CIDRRangeQueryTest extends CrateDummyClusterServiceUnitTest {

    @Test
    public void test_ipv4_cidr_operator() throws Throwable {
        test(
            new String[]{"192.168.0.1", "192.168.1.1", "192.168.1.7", "192.168.1.255", "192.168.2.0", "10.0.0.1"},
            "ip_addr << '192.168.1.1/24'",
            "192.168.1.1", "192.168.1.7", "192.168.1.255");
    }

    @Test
    public void test_ipv4_cidr_operator_right_operand_is_ip() throws Throwable {
        // operand [192.168.1.0] must conform with CIDR notation
        assertThatThrownBy(
            () -> test(
                new String[]{"192.168.0.1", "192.168.1.1", "192.168.1.7", "192.168.1.255", "192.168.2.0", "10.0.0.1"},
                "ip_addr << '192.168.1.0'::ip",
                "192.168.1.1", "192.168.1.7"))
            .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void test_ipv4_cidr_operator_right_operand_is_text() throws Throwable {
        // operand [random text] must conform with CIDR notation
        assertThatThrownBy(
            () -> test(
                new String[]{"192.168.0.1", "192.168.1.1", "192.168.1.7", "192.168.1.255", "192.168.2.0", "10.0.0.1"},
                "ip_addr << 'random text'",
                "192.168.1.1", "192.168.1.7"))
            .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    private void test(Object[] valuesToIndex, String queryStr, Object... expectedResults) throws Throwable {
        QueryTester.Builder builder = new QueryTester.Builder(
            THREAD_POOL,
            clusterService,
            Version.CURRENT,
            "create table t1 (ip_addr ip)"
        );
        builder.indexValues("ip_addr", valuesToIndex);
        try (QueryTester tester = builder.build()) {
            assertThat(tester.runQuery("ip_addr", queryStr)).contains(expectedResults);
        }

        // test ip col with index off
        builder = new QueryTester.Builder(
            THREAD_POOL,
            clusterService,
            Version.CURRENT,
            "create table t2 (ip_addr ip index off)"
        );
        builder.indexValues("ip_addr", valuesToIndex);
        try (QueryTester tester = builder.build()) {
            assertThat(tester.runQuery("ip_addr", queryStr)).contains(expectedResults);
        }
    }
}

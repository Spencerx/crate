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

package io.crate.expression.scalar.postgres;

import static io.crate.testing.Asserts.isLiteral;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import io.crate.expression.scalar.ScalarTestCase;

public class PgBackendPidFunctionTest extends ScalarTestCase {

    @Test
    public void testPgBackendPid() throws Exception {
        assertEvaluate("pg_backend_pid()", -1);
        assertNormalize("pg_backend_pid()", isLiteral(-1));
    }

    @Test
    public void testPgBackendPidWithFQNFunctionName() throws Exception {
        assertEvaluate("pg_catalog.pg_backend_pid()", -1);
    }

    @Test
    public void testInvalidType() throws Exception {
        assertThatThrownBy(() -> sqlExpressions.asSymbol("pg_backend_pid(null)"))
            .hasMessage(
                "Invalid arguments in: pg_backend_pid(NULL) with (undefined). Valid types: ()");
    }
}

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

package io.crate.integrationtests;

import static io.crate.protocols.postgres.PGErrorStatus.INTERNAL_ERROR;
import static io.crate.protocols.postgres.PGErrorStatus.UNDEFINED_COLUMN;
import static io.crate.protocols.postgres.PGErrorStatus.UNDEFINED_TABLE;
import static io.crate.testing.Asserts.assertThat;
import static io.crate.testing.TestingHelpers.resolveCanonicalString;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.lucene.util.Version;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.test.IntegTestCase;
import org.junit.Before;
import org.junit.Test;

import io.crate.exceptions.OperationOnInaccessibleRelationException;
import io.crate.metadata.RelationName;
import io.crate.metadata.blob.BlobSchemaInfo;
import io.crate.testing.Asserts;
import io.crate.testing.SQLResponse;
import io.crate.testing.TestingHelpers;
import io.crate.testing.UseJdbc;

@IntegTestCase.ClusterScope(numClientNodes = 0, numDataNodes = 2, supportsDedicatedMasters = false)
public class SysShardsTest extends IntegTestCase {

    @Before
    public void initTestData() throws Exception {
        Setup setup = new Setup(sqlExecutor);
        setup.groupBySetup();
        execute("create table quotes (id integer primary key, quote string) with (number_of_replicas=1)");
        execute("create blob table blobs clustered into 5 shards with (number_of_replicas=1)");
        ensureGreen();
    }

    @Test
    public void testPathAndBlobPath() throws Exception {
        Path blobs = createTempDir("blobs");
        execute("create table t1 (x int) clustered into 1 shards with (number_of_replicas = 0)");
        execute("create blob table b1 clustered into 1 shards with (number_of_replicas = 0)");
        execute("create blob table b2 " +
            "clustered into 1 shards with (number_of_replicas = 0, blobs_path = '" + blobs.toString() + "')");
        ensureYellow();

        execute("select path, blob_path from sys.shards where table_name in ('t1', 'b1', 'b2') " +
            "order by table_name asc");
        // b1
        // path + /blobs == blob_path without custom blob path
        assertThat(response.rows()[0][0] + resolveCanonicalString("/blobs")).isEqualTo(response.rows()[0][1]);

        ClusterService clusterService = cluster().getInstance(ClusterService.class);
        Metadata metadata = clusterService.state().metadata();
        IndexMetadata index = metadata.getIndex(new RelationName(BlobSchemaInfo.NAME, "b2"), List.of(), true, im -> im);
        String indexUUID = index.getIndexUUID();

        // b2
        String b2Path = (String) response.rows()[1][0];
        assertThat(b2Path)
            .contains(resolveCanonicalString("/nodes/"))
            .endsWith(resolveCanonicalString("/indices/" + indexUUID + "/0"));

        String b2BlobPath = (String) response.rows()[1][1];
        assertThat(b2BlobPath)
            .contains(resolveCanonicalString("/nodes/"))
            .endsWith(resolveCanonicalString("/indices/" + indexUUID + "/0/blobs"));
        // t1
        assertThat(response.rows()[2][1]).isNull();
    }

    @Test
    public void testSelectGroupByWhereTable() throws Exception {
        SQLResponse response = execute(
            "select count(*), num_docs from sys.shards where table_name = 'characters' " +
                "group by num_docs order by count(*)");
        assertThat(response.rowCount()).isGreaterThan(0L);
    }

    @Test
    public void testSelectGroupByAllTables() throws Exception {
        SQLResponse response = execute("select count(*), table_name from sys.shards " +
            "group by table_name order by table_name");
        assertThat(response.rowCount()).isEqualTo(3L);
        assertThat(response.rows()[0][0]).isEqualTo(10L);
        assertThat(response.rows()[0][1]).isEqualTo("blobs");
        assertThat(response.rows()[1][1]).isEqualTo("characters");
        assertThat(response.rows()[2][1]).isEqualTo("quotes");
    }

    @Test
    public void testGroupByWithLimitUnassignedShards() throws Exception {
        try {
            execute("create table t (id int, name string) with (number_of_replicas=2, \"write.wait_for_active_shards\"=1)");
            ensureYellow();

            SQLResponse response = execute("select sum(num_docs), table_name, sum(num_docs) from sys.shards group by table_name order by table_name desc limit 1000");
            assertThat(response).hasRows(
                "0| t| 0",
                "0| quotes| 0",
                "14| characters| 14",
                "0| blobs| 0");
        } finally {
            execute("drop table t");
        }
    }

    @Test
    public void testSelectGroupByWhereNotLike() throws Exception {
        SQLResponse response = execute("select count(*), table_name from sys.shards " +
            "where table_name not like 'my_table%' group by table_name order by table_name");
        assertThat(response.rowCount()).isEqualTo(3L);
        assertThat(response.rows()[0][0]).isEqualTo(10L);
        assertThat(response.rows()[0][1]).isEqualTo("blobs");
        assertThat(response.rows()[1][0]).isEqualTo(8L);
        assertThat(response.rows()[1][1]).isEqualTo("characters");
        assertThat(response.rows()[2][0]).isEqualTo(8L);
        assertThat(response.rows()[2][1]).isEqualTo("quotes");
    }

    @Test
    public void testSelectWhereTable() throws Exception {
        SQLResponse response = execute(
            "select id, size from sys.shards " +
                "where table_name = 'characters'");
        assertThat(response.rowCount()).isEqualTo(8L);
    }

    @Test
    public void testSelectStarWhereTable() throws Exception {
        SQLResponse response = execute(
            "select * from sys.shards where table_name = 'characters'");
        assertThat(response.rowCount()).isEqualTo(8L);
    }

    @Test
    public void testSelectStarAllTables() throws Exception {
        SQLResponse response = execute("select * from sys.shards");
        assertThat(response.rowCount()).isEqualTo(26L);
        assertThat(response.cols()).containsExactly(
            "blob_path",
            "closed",
            "flush_stats",
            "id",
            "last_write_before",
            "min_lucene_version",
            "node",
            "num_docs",
            "orphan_partition",
            "partition_ident",
            "partition_uuid",
            "path",
            "primary",
            "recovery",
            "relocating_node",
            "retention_leases",
            "routing_state",
            "schema_name",
            "seq_no_stats",
            "size",
            "state",
            "table_name",
            "translog_stats");
    }

    @Test
    public void testSelectStarLike() throws Exception {
        SQLResponse response = execute(
            "select * from sys.shards where table_name like 'charact%'");
        assertThat(response.rowCount()).isEqualTo(8L);
    }

    @Test
    public void testSelectStarNotLike() throws Exception {
        SQLResponse response = execute(
            "select * from sys.shards where table_name not like 'quotes%'");
        assertThat(response.rowCount()).isEqualTo(18L);
    }

    @Test
    public void testSelectStarIn() throws Exception {
        SQLResponse response = execute(
            "select * from sys.shards where table_name in ('characters')");
        assertThat(response.rowCount()).isEqualTo(8L);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_translog_stats_can_be_retrieved() {
        execute("SELECT translog_stats, translog_stats['size'] FROM sys.shards " +
            "WHERE id = 0 AND \"primary\" = true AND table_name = 'characters'");
        Object[] resultRow = response.rows()[0];
        Map<String, Object> translogStats = (Map<String, Object>) resultRow[0];
        assertThat(((Number) translogStats.get("size")).longValue()).isEqualTo(resultRow[1]);
        assertThat(((Number) translogStats.get("uncommitted_size")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(((Number) translogStats.get("number_of_operations")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(((Number) translogStats.get("uncommitted_operations")).longValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    public void testSelectStarMatch() throws Exception {
        Asserts.assertSQLError(() -> execute("select * from sys.shards where match(table_name, 'characters')"))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(BAD_REQUEST, 4004)
            .hasMessageContaining("Cannot use MATCH on system tables");
    }

    @Test
    public void testSelectOrderBy() throws Exception {
        SQLResponse response = execute("select table_name, min_lucene_version, * " +
            "from sys.shards order by table_name");
        assertThat(response.rowCount()).isEqualTo(26L);
        List<String> tableNames = Arrays.asList("blobs", "characters", "quotes");
        for (Object[] row : response.rows()) {
            assertThat(tableNames.contains(row[0])).isTrue();
            assertThat(row[1]).isEqualTo(Version.LATEST.toString());
        }
    }

    @Test
    public void testSelectGreaterThan() throws Exception {
        SQLResponse response = execute("select * from sys.shards where num_docs > 0");
        assertThat(response.rowCount()).isGreaterThan(0L);
    }

    @Test
    public void testSelectWhereBoolean() throws Exception {
        SQLResponse response = execute("select * from sys.shards where \"primary\" = false");
        assertThat(response.rowCount()).isEqualTo(13L);
    }

    @Test
    public void testSelectGlobalAggregates() throws Exception {
        SQLResponse response = execute(
            "select sum(size), min(size), max(size), avg(size) from sys.shards");
        assertThat(response.rowCount()).isEqualTo(1L);
        assertThat(response.rows()[0].length).isEqualTo(4);
        assertThat(response.rows()[0][0]).isNotNull();
        assertThat(response.rows()[0][1]).isNotNull();
        assertThat(response.rows()[0][2]).isNotNull();
        assertThat(response.rows()[0][3]).isNotNull();
    }

    @Test
    public void testSelectGlobalCount() throws Exception {
        SQLResponse response = execute("select count(*) from sys.shards");
        assertThat(response.rowCount()).isEqualTo(1L);
        assertThat(response.rows()[0][0]).isEqualTo(26L);
    }

    @Test
    public void testSelectGlobalCountAndOthers() throws Exception {
        SQLResponse response = execute("select count(*), max(table_name) from sys.shards");
        assertThat(response.rowCount()).isEqualTo(1L);
        assertThat(response.rows()[0][0]).isEqualTo(26L);
        assertThat(response.rows()[0][1]).isEqualTo("quotes");
    }

    @Test
    public void testGroupByUnknownResultColumn() throws Exception {
        Asserts.assertSQLError(() -> execute("select lol from sys.shards group by table_name"))
            .hasPGError(UNDEFINED_COLUMN)
            .hasHTTPError(NOT_FOUND, 4043)
            .hasMessageContaining("Column lol unknown");
    }

    @Test
    public void testGroupByUnknownGroupByColumn() throws Exception {
        Asserts.assertSQLError(() -> execute("select max(num_docs) from sys.shards group by lol"))
            .hasPGError(UNDEFINED_COLUMN)
            .hasHTTPError(NOT_FOUND, 4043)
            .hasMessageContaining("Column lol unknown");
    }

    @Test
    public void testGroupByUnknownOrderBy() throws Exception {
        Asserts.assertSQLError(() -> execute(
                "select sum(num_docs), table_name from sys.shards group by table_name order by lol"))
            .hasPGError(UNDEFINED_COLUMN)
            .hasHTTPError(NOT_FOUND, 4043)
            .hasMessageContaining("Column lol unknown");
    }

    @Test
    public void testGroupByUnknownWhere() throws Exception {
        Asserts.assertSQLError(() -> execute(
                "select sum(num_docs), table_name from sys.shards where lol='funky' group by table_name"))
            .hasPGError(UNDEFINED_COLUMN)
            .hasHTTPError(NOT_FOUND, 4043)
            .hasMessageContaining("Column lol unknown");
    }

    @Test
    public void testGlobalAggregateUnknownWhere() throws Exception {
        Asserts.assertSQLError(() -> execute(
                "select sum(num_docs) from sys.shards where lol='funky'"))
            .hasPGError(UNDEFINED_COLUMN)
            .hasHTTPError(NOT_FOUND, 4043)
            .hasMessageContaining("Column lol unknown");
    }

    @Test
    public void testSelectShardIdFromSysNodes() throws Exception {
        Asserts.assertSQLError(() -> execute(
                "select sys.shards.id from sys.nodes"))
            .hasPGError(UNDEFINED_TABLE)
            .hasHTTPError(NOT_FOUND, 4041)
            .hasMessageContaining("Relation 'sys.shards' unknown");
    }

    @Test
    public void testSelectWithOrderByColumnNotInOutputs() throws Exception {
        // regression test... query failed with ArrayOutOfBoundsException due to inputColumn mangling in planner
        SQLResponse response = execute("select id from sys.shards order by table_name limit 1");
        assertThat(response.rowCount()).isEqualTo(1L);
        assertThat(response.rows()[0][0]).isExactlyInstanceOf(Integer.class);
    }

    @Test
    public void testSelectGroupByHaving() throws Exception {
        SQLResponse response = execute("select count(*) " +
            "from sys.shards " +
            "group by table_name " +
            "having table_name = 'quotes'");
        assertThat(TestingHelpers.printedTable(response.rows())).isEqualTo("8\n");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSelectNodeSysExpression() throws Exception {
        SQLResponse response = execute(
            "select node, node['name'], id from sys.shards order by node['name'], id  limit 1");
        assertThat(response.rowCount()).isEqualTo(1L);
        Map<String, Object> fullNode = (Map<String, Object>) response.rows()[0][0];
        String nodeName = response.rows()[0][1].toString();
        assertThat(nodeName).isEqualTo("node_s0");
        assertThat(fullNode.get("name")).isEqualTo("node_s0");
    }

    @Test
    public void testSelectNodeSysExpressionWithUnassignedShards() throws Exception {
        try {
            execute("create table users (id integer primary key, name string) " +
                "clustered into 5 shards with (number_of_replicas=2, \"write.wait_for_active_shards\"=1)");
            ensureYellow();
            SQLResponse response = execute(
                "select node['name'], id from sys.shards where table_name = 'users' order by node['name'] nulls last"
            );
            String nodeName = response.rows()[0][0].toString();
            assertThat(nodeName).isEqualTo("node_s0");
            assertThat(response.rows()[12][0]).isNull();
        } finally {
            execute("drop table users");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSelectRecoveryExpression() throws Exception {
        SQLResponse response = execute("select recovery, " +
            "recovery['files'], recovery['files']['used'], recovery['files']['reused'], recovery['files']['recovered'], " +
            "recovery['size'], recovery['size']['used'], recovery['size']['reused'], recovery['size']['recovered'] " +
            "from sys.shards");
        for (Object[] row : response.rows()) {
            Map<String, Object> recovery = (Map<String, Object>) row[0];
            Map<String, Integer> files = (Map<String, Integer>) row[1];
            assertThat(((Map<String, Integer>) recovery.get("files")).entrySet()).isEqualTo(files.entrySet());
            Map<String, Long> size = (Map<String, Long>) row[5];
            assertThat(((Map<String, Long>) recovery.get("size")).entrySet()).isEqualTo(size.entrySet());
        }
    }

    @Test
    public void testScalarEvaluatesInErrorOnSysShards() throws Exception {
        // we need at least 1 shard, otherwise the table is empty and no evaluation occurs
        execute("create table t1 (id integer) clustered into 1 shards with (number_of_replicas=0)");
        ensureYellow();
        Asserts.assertSQLError(() -> execute(
                "select 1/0 from sys.shards"))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(BAD_REQUEST, 4000)
            .hasMessageContaining("/ by zero");
    }

    @Test
    public void test_state_for_shards_of_closed_table() throws Exception {
        execute("create table doc.tbl (x int) clustered into 2 shards with(number_of_replicas=0)");
        ensureGreen(); // All shards must be available before close; otherwise they're skipped
        execute("insert into doc.tbl values(1)");
        logger.info("---> Closing table doc.tbl");
        execute("alter table doc.tbl close");
        execute("select id, closed from sys.shards where table_name = 'tbl' order by id asc");
        assertThat(response).hasRows(
            "0| true",
            "1| true"
        );
    }

    @UseJdbc(0)
    @Test
    public void testSelectFromClosedTableNotAllowed() throws Exception {
        int numberOfReplicas = numberOfReplicas();
        execute(
            "create table doc.tbl (x int) with (number_of_replicas = ?)",
            new Object[]{numberOfReplicas}
        );
        execute("insert into doc.tbl values(1)");
        ensureGreen(); // All shards must be available before close; otherwise they're skipped
        execute("alter table doc.tbl close");
        assertBusy(() ->
            assertThat(execute("select closed from information_schema.tables where table_name = 'tbl'")).hasRows("true")
        );
        assertThatThrownBy(() -> execute("select * from doc.tbl"))
            .satisfiesAnyOf(
                e -> Asserts.assertThat(e)
                    .isExactlyInstanceOf(OperationOnInaccessibleRelationException.class)
                    .hasMessage("The relation \"doc.tbl\" doesn't support or allow READ operations, as it is currently closed."),
                e -> Asserts.assertThat(e)
                    .isExactlyInstanceOf(ClusterBlockException.class)
                    .hasMessageContaining("Table or partition preparing to close."));
    }

    @UseJdbc(0)
    @Test
    public void testInsertFromClosedTableNotAllowed() throws Exception {
        execute("create table doc.tbl (x int)");
        execute("insert into doc.tbl values(1)");
        execute("alter table doc.tbl close");
        waitNoPendingTasksOnAll(); // ensure close is processed
        assertThatThrownBy(() -> execute("insert into doc.tbl values(2)"))
            .satisfiesAnyOf(
                e -> Asserts.assertThat(e)
                    .isExactlyInstanceOf(OperationOnInaccessibleRelationException.class)
                    .hasMessage("The relation \"doc.tbl\" doesn't support or allow INSERT operations, as it is currently closed."),
                e -> Asserts.assertThat(e)
                    .isExactlyInstanceOf(ClusterBlockException.class)
                    .hasMessageContaining("Table or partition preparing to close."));
    }

    @Test
    public void test_last_write_before() {
        long startTime = System.currentTimeMillis();
        execute("create table doc.tbl (x int) clustered into 1 shards with(number_of_replicas=0)");
        execute("insert into doc.tbl values(1)");
        execute("refresh table doc.tbl");

        execute("select last_write_before from sys.shards where table_name = 'tbl'");
        long lastTimeBefore1 = (long) response.rows()[0][0];
        assertThat(lastTimeBefore1).isGreaterThan(startTime);

        execute("update doc.tbl set x = x + 1");
        execute("refresh table doc.tbl");
        execute("select last_write_before from sys.shards where table_name = 'tbl'");
        long lastTimeBefore2 = (long) response.rows()[0][0];
        assertThat(lastTimeBefore2).isGreaterThan(lastTimeBefore1);
    }
}

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

package io.crate.analyze.where;

import static io.crate.planner.WhereClauseOptimizer.optimize;
import static io.crate.testing.Asserts.assertThat;
import static io.crate.testing.Asserts.isLiteral;
import static io.crate.testing.Asserts.isReference;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import io.crate.analyze.AnalyzedDeleteStatement;
import io.crate.analyze.AnalyzedStatement;
import io.crate.analyze.AnalyzedUpdateStatement;
import io.crate.analyze.QueriedSelectRelation;
import io.crate.analyze.WhereClause;
import io.crate.analyze.relations.DocTableRelation;
import io.crate.data.Row;
import io.crate.exceptions.ConversionException;
import io.crate.exceptions.VersioningValidationException;
import io.crate.expression.eval.EvaluatingNormalizer;
import io.crate.expression.operator.EqOperator;
import io.crate.expression.operator.LikeOperators;
import io.crate.expression.operator.any.AnyEqOperator;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.PartitionName;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.doc.DocSchemaInfo;
import io.crate.metadata.settings.CoordinatorSessionSettings;
import io.crate.planner.WhereClauseOptimizer.DetailedQuery;
import io.crate.planner.operators.SubQueryResults;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SQLExecutor;
import io.crate.types.ArrayType;
import io.crate.types.DataTypes;

public class WhereClauseAnalyzerTest extends CrateDummyClusterServiceUnitTest {

    private final CoordinatorTxnCtx coordinatorTxnCtx = new CoordinatorTxnCtx(CoordinatorSessionSettings.systemDefaults());
    private SQLExecutor e;

    @Before
    public void prepare() throws IOException {
        e = SQLExecutor.of(clusterService);
        registerTables(e);
    }

    private void registerTables(SQLExecutor builder) throws IOException {
        builder.addTable(
            "create table users (" +
             "  id string primary key," +
             "  name string," +
             "  tags array(string)" +
             ") clustered by (id)");
        RelationName docParted = new RelationName("doc", "parted");
        builder.addTable(
            "create table doc.parted (" +
            "   id integer," +
            "   name string," +
            "   date timestamp with time zone," +
            "   obj object (ignored)" +
            ") partitioned by (date)",
            new PartitionName(docParted, singletonList("1395874800000")).asIndexName(),
            new PartitionName(docParted, singletonList("1395961200000")).asIndexName(),
            new PartitionName(docParted, singletonList(null)).asIndexName()
        );
        RelationName docBooleanParted = new RelationName("doc", "bool_parted");
        builder.addTable(
            "create table doc.bool_parted (" +
                "   id boolean" +
                ") partitioned by (id)",
            new PartitionName(docBooleanParted, singletonList("false")).asIndexName(),
            new PartitionName(docBooleanParted, singletonList("true")).asIndexName(),
            new PartitionName(docBooleanParted, singletonList(null)).asIndexName()
        );
        builder.addTable(
            "create table doc.users_multi_pk (" +
            "   id long primary key," +
            "   name string primary key," +
            "   details object," +
            "   awesome boolean," +
            "   friends array(object)" +
            ") clustered by (id)"
        );
        builder.addTable(
            "create table doc.pk4 (" +
            "   i1 integer primary key," +
            "   i2 integer primary key," +
            "   i3 integer primary key," +
            "   i4 integer primary key" +
            ")"
        );
        RelationName docGeneratedCol = new RelationName("doc", "generated_col");
        builder.addTable(
            "create table doc.generated_col (" +
                "   ts timestamp with time zone ," +
                "   x integer," +
                "   y long," +
                "   day as date_trunc('day', ts)," +
                "   minus_y as y * -1," +
                "   x_incr as x + 1" +
                ") partitioned by (day, minus_y)",
            new PartitionName(docGeneratedCol, List.of("1420070400000", "-1")).asIndexName(),
            new PartitionName(docGeneratedCol, List.of("1420156800000", "-2")).asIndexName()
        );
        RelationName docDoubleGenParted = new RelationName(DocSchemaInfo.NAME, "double_gen_parted");
        builder.addTable(
            "create table doc.double_gen_parted (" +
                "   x integer," +
                "   x1 as x + 1," +
                "   x2 as x + 2" +
                ") partitioned by (x1, x2)",
            new PartitionName(docDoubleGenParted, List.of("4", "5")).toString(),
            new PartitionName(docDoubleGenParted, List.of("5", "6")).toString()
        );
    }

    private WhereClause analyzeWhere(String stmt) {
        AnalyzedStatement rel = e.analyze(stmt);
        if (rel instanceof QueriedSelectRelation queriedRelation) {
            if (queriedRelation.from().get(0) instanceof DocTableRelation docTableRelation) {
                return analyzeWhere(docTableRelation, queriedRelation.where());
            }
            return new WhereClause(queriedRelation.where());
        } else if (rel instanceof AnalyzedDeleteStatement analyzedDeleteStatement) {
            if (analyzedDeleteStatement.relation() instanceof DocTableRelation docTableRelation) {
                return analyzeWhere(docTableRelation, analyzedDeleteStatement.query());
            }
            return new WhereClause(analyzedDeleteStatement.query());
        } else {
            return WhereClause.MATCH_ALL;
        }
    }

    private WhereClause analyzeWhere(DocTableRelation docTableRelation, Symbol query) {
        DetailedQuery detailedQuery = optimize(
            new EvaluatingNormalizer(e.nodeCtx, RowGranularity.CLUSTER, null, docTableRelation),
            query,
            docTableRelation.tableInfo(),
            coordinatorTxnCtx,
            e.nodeCtx,
            e.getPlannerContext().timeoutToken());
        return detailedQuery.toBoundWhereClause(
            docTableRelation.tableInfo(),
            Row.EMPTY,
            SubQueryResults.EMPTY,
            coordinatorTxnCtx,
            e.nodeCtx,
            e.getPlannerContext().clusterState().metadata());
    }

    @Test
    public void testSelectWherePartitionedByColumn() throws Exception {
        WhereClause whereClause = analyzeWhere("select id from parted where date = 1395874800000");
        assertThat(whereClause.queryOrFallback()).isLiteral(true);
        assertThat(whereClause.partitions()).containsExactly(
            new PartitionName(new RelationName("doc", "parted"), List.of("1395874800000")).asIndexName());
    }

    @Test
    public void test_partitioned_by_boolean_col() throws Exception {
        var values = List.of("true", "false");
        for (var v : values) {
            WhereClause whereClause = analyzeWhere("select id from bool_parted where id = " + v);
            assertThat(whereClause.queryOrFallback()).isLiteral(true);
            assertThat(whereClause.partitions()).containsExactly(
                new PartitionName(new RelationName("doc", "bool_parted"), List.of(v)).asIndexName());
        }
    }

    @Test
    public void testUpdateWherePartitionedByColumn() throws Exception {
        AnalyzedUpdateStatement update = e.analyze("update parted set id = 2 where date = 1395874800000::timestamptz");
        assertThat(update.query()).isFunction(EqOperator.NAME, isReference("date"), isLiteral(1395874800000L));
    }

    @Test
    public void testSelectFromPartitionedTable() throws Exception {
        String partition1 = new PartitionName(new RelationName("doc", "parted"), List.of("1395874800000")).asIndexName();
        String partition2 = new PartitionName(new RelationName("doc", "parted"), List.of("1395961200000")).asIndexName();
        String partition3 = new PartitionName(new RelationName("doc", "parted"), singletonList(null)).asIndexName();

        WhereClause whereClause = analyzeWhere("select id, name from parted where date = 1395874800000");
        assertThat(whereClause.partitions()).containsExactly(partition1);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date = 1395874800000 " +
                                         "and substr(name, 0, 4) = 'this'");
        assertThat(whereClause.partitions()).containsExactly(partition1);
        assertThat(whereClause.hasQuery()).isTrue();

        whereClause = analyzeWhere("select id, name from parted where date >= 1395874800000");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date < 1395874800000");
        assertThat(whereClause.partitions()).isEmpty();
        assertThat(whereClause.queryOrFallback()).isLiteral(false);

        whereClause = analyzeWhere("select id, name from parted where date = 1395874800000 and date = 1395961200000");
        assertThat(whereClause.partitions()).isEmpty();
        assertThat(whereClause.queryOrFallback()).isLiteral(false);

        whereClause = analyzeWhere("select id, name from parted where date = 1395874800000 or date = 1395961200000");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date < 1395874800000 or date > 1395874800000");
        assertThat(whereClause.partitions()).containsExactly(partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date in (1395874800000, 1395961200000)");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date in (1395874800000, 1395961200000) and id = 1");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isFunction("op_=");

        /*
         * obj['col'] = 'undefined' => null as col doesn't exist
         *
         *  partition1: not (true  and null) -> not (null)  -> null -> no match
         *  partition2: not (false and null) -> not (false) -> true -> match
         *  partition3: not (null  and null) -> not (null)  -> null -> no match
         */
        whereClause = analyzeWhere("select id, name from parted where not (date = 1395874800000 and obj['col'] = 'undefined')");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date in (1395874800000) or date in (1395961200000)");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where date = 1395961200000 and id = 1");
        assertThat(whereClause.partitions()).containsExactly(partition2);
        assertThat(whereClause.queryOrFallback()).isFunction("op_=");

        whereClause = analyzeWhere("select id, name from parted where (date =1395874800000 or date = 1395961200000) and id = 1");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isFunction("op_=");

        whereClause = analyzeWhere("select id, name from parted where date = 1395874800000 and id is null");
        assertThat(whereClause.partitions()).containsExactly(partition1);
        assertThat(whereClause.queryOrFallback()).isFunction("op_isnull");

        whereClause = analyzeWhere("select id, name from parted where date is null and id = 1");
        assertThat(whereClause.partitions()).containsExactly(partition3);
        assertThat(whereClause.queryOrFallback()).isFunction("op_=");

        whereClause = analyzeWhere("select id, name from parted where 1395874700000 < date and date < 1395961200001");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);

        whereClause = analyzeWhere("select id, name from parted where '2014-03-16T22:58:20' < date and date < '2014-03-27T23:00:01'");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(partition1, partition2);
        assertThat(whereClause.queryOrFallback()).isLiteral(true);
    }

    @Test
    public void test_delete_from_partition_by_column_involving_non_deterministic_function() {
        var whereClause = analyzeWhere("delete from parted where date < now() or date is null");
        assertThat(whereClause.partitions()).hasSize(3); // deleting all partitions
        assertThat(whereClause.queryOrFallback()).isLiteral(true);
    }

    @Test
    public void test_where_on_date_with_null_partition_or_id_can_match_all_partitions() throws Exception {
        WhereClause whereClause = analyzeWhere(
            "select id, name from parted where date = 1395961200000::timestamptz or id = 1");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(
            ".partitioned.parted.0400",
            ".partitioned.parted.04732cpp6ksjcc9i60o30c1g",
            ".partitioned.parted.04732cpp6ks3ed1o60o30c1g");
        assertThat(whereClause.queryOrFallback())
            .isSQL("((doc.parted.date = 1395961200000::bigint) OR (doc.parted.id = 1))");
    }

    @Test
    public void testAnyInvalidArrayType() throws Exception {
        assertThatThrownBy(
            () -> analyzeWhere("select * from users_multi_pk where awesome = any(['foo', 'bar', 'baz'])"))
            .isExactlyInstanceOf(ConversionException.class)
            .hasMessage("Cannot cast `'foo'` of type `text` to type `boolean`");
    }

    @Test
    public void testInConvertedToAnyIfOnlyLiterals() throws Exception {
        StringBuilder sb = new StringBuilder("select id from sys.shards where id in (");
        int i = 0;
        for (; i < 1500; i++) {
            sb.append(i);
            sb.append(',');
        }
        sb.append(i);
        sb.append(')');
        String s = sb.toString();

        WhereClause whereClause = analyzeWhere(s);
        assertThat(whereClause.query())
            .isFunction(AnyEqOperator.NAME, List.of(DataTypes.INTEGER, new ArrayType<>(DataTypes.INTEGER)));
    }

    @Test
    public void testAnyLikeArrayLiteral() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from users where name like any(['a', 'b', 'c'])");
        assertThat(whereClause.query())
            .isFunction(LikeOperators.ANY_LIKE, List.of(DataTypes.STRING, new ArrayType<>(DataTypes.STRING)));
    }

    @Test
    public void testAnyILikeArrayLiteral() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from users where name ilike any(['a', 'b', 'c'])");
        assertThat(whereClause.query())
            .isFunction(LikeOperators.ANY_ILIKE, List.of(DataTypes.STRING, new ArrayType<>(DataTypes.STRING)));
    }

    @Test
    public void testEqualGenColOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where y = 1");
        assertThat(whereClause.partitions()).containsExactly(
            new PartitionName(new RelationName("doc", "generated_col"), List.of("1420070400000", "-1")).asIndexName());
    }

    @Test
    public void testNonPartitionedNotOptimized() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where x = 1");
        assertThat(whereClause.query()).isSQL("(doc.generated_col.x = 1)");
    }

    @Test
    public void testGtGenColOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where ts > '2015-01-02T12:00:00'");
        assertThat(whereClause.partitions()).containsExactly(
            new PartitionName(new RelationName("doc", "generated_col"), List.of("1420156800000", "-2")).asIndexName());
    }

    @Test
    public void testGenColRoundingFunctionNoSwappingOperatorOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where ts >= '2015-01-02T12:00:00'");
        assertThat(whereClause.partitions()).containsExactly(
            new PartitionName(new RelationName("doc", "generated_col"), List.of("1420156800000", "-2")).asIndexName());
    }

    @Test
    public void testMultiplicationGenColNoOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where y > 1");
        // no optimization is done
        assertThat(whereClause.partitions()).isEmpty();
        assertThat(whereClause.queryOrFallback()).isFunction("op_>");
    }

    @Test
    public void testMultipleColumnsOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where ts > '2015-01-01T12:00:00' and y = 1");
        assertThat(whereClause.partitions()).containsExactly(
            new PartitionName(new RelationName("doc", "generated_col"), List.of("1420070400000", "-1")).asIndexName());
    }

    @Test
    public void testColumnReferencedTwiceInGeneratedColumnPartitioned() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from double_gen_parted where x = 4");
        assertThat(whereClause.query()).isSQL("(doc.double_gen_parted.x = 4)");
        assertThat(whereClause.partitions()).containsExactly(".partitioned.double_gen_parted.0813a0hm");
    }

    @Test
    public void testOptimizationNonRoundingFunctionGreater() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from double_gen_parted where x > 3");
        assertThat(whereClause.query()).isSQL("(doc.double_gen_parted.x > 3)");
        assertThat(whereClause.partitions()).containsExactly(".partitioned.double_gen_parted.0813a0hm");
    }

    @Test
    public void testGenColRangeOptimization() throws Exception {
        WhereClause whereClause = analyzeWhere("select * from generated_col where ts >= '2015-01-01T12:00:00' and ts <= '2015-01-02T00:00:00'");
        RelationName relationName = new RelationName("doc", "generated_col");
        assertThat(whereClause.partitions()).containsExactlyInAnyOrder(
            new PartitionName(relationName, List.of("1420070400000", "-1")).asIndexName(),
            new PartitionName(relationName, List.of("1420156800000", "-2")).asIndexName());
    }

    @Test
    public void testRawNotAllowedInQuery() throws Exception {
        assertThatThrownBy(() -> analyzeWhere("select * from users where _raw = 'foo'"))
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("The _raw column is not searchable and cannot be used inside a query");
    }

    @Test
    public void testVersionOnlySupportedWithEqualOperator() throws Exception {
        assertThatThrownBy(() -> analyzeWhere("select * from users where _version > 1"))
            .isExactlyInstanceOf(VersioningValidationException.class)
            .hasMessage(VersioningValidationException.VERSION_COLUMN_USAGE_MSG);
    }

    @Test
    public void testSeqNoOnlySupportedWithEqualOperator() throws Exception {
        assertThatThrownBy(() -> analyzeWhere("select * from users where _seq_no > 1"))
            .isExactlyInstanceOf(VersioningValidationException.class)
            .hasMessage(VersioningValidationException.SEQ_NO_AND_PRIMARY_TERM_USAGE_MSG);
    }

    @Test
    public void testPrimaryTermOnlySupportedWithEqualOperator() throws Exception {
        assertThatThrownBy(() -> analyzeWhere("select * from users where _primary_term > 1"))
            .isExactlyInstanceOf(VersioningValidationException.class)
            .hasMessage(VersioningValidationException.SEQ_NO_AND_PRIMARY_TERM_USAGE_MSG);
    }

    @Test
    public void testSeqNoAndPrimaryTermAreRequired() {
        assertThatThrownBy(
            () -> analyzeWhere("select * from users where name = 'Douglas' and _primary_term = 1"))
            .isExactlyInstanceOf(VersioningValidationException.class)
            .hasMessage(VersioningValidationException.SEQ_NO_AND_PRIMARY_TERM_USAGE_MSG);
    }

    @Test
    public void testVersioningMechanismsCannotBeMixed() {
        assertThatThrownBy(
            () -> analyzeWhere("select * from users where name = 'Douglas' and _primary_term = 1 and _seq_no = 22 and _version = 1"))
            .isExactlyInstanceOf(VersioningValidationException.class)
            .hasMessage(VersioningValidationException.MIXED_VERSIONING_COLUMNS_USAGE_MSG);
    }
}

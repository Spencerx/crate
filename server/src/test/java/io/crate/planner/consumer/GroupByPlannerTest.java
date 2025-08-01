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

package io.crate.planner.consumer;

import static io.crate.testing.Asserts.assertThat;
import static io.crate.testing.Asserts.isReference;
import static java.util.Collections.singletonList;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import io.crate.analyze.TableDefinitions;
import io.crate.execution.dsl.phases.CollectPhase;
import io.crate.execution.dsl.phases.MergePhase;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.execution.dsl.projection.EvalProjection;
import io.crate.execution.dsl.projection.FilterProjection;
import io.crate.execution.dsl.projection.GroupProjection;
import io.crate.execution.dsl.projection.LimitAndOffsetProjection;
import io.crate.execution.dsl.projection.OrderedLimitAndOffsetProjection;
import io.crate.execution.dsl.projection.Projection;
import io.crate.execution.engine.aggregation.impl.CountAggregation;
import io.crate.expression.symbol.AggregateMode;
import io.crate.expression.symbol.Aggregation;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.InputColumn;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.SymbolType;
import io.crate.metadata.PartitionName;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.planner.ExecutionPlan;
import io.crate.planner.Merge;
import io.crate.planner.PositionalOrderBy;
import io.crate.planner.node.dql.Collect;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.Asserts;
import io.crate.testing.SQLExecutor;
import io.crate.types.DataTypes;

public class GroupByPlannerTest extends CrateDummyClusterServiceUnitTest {

    @Test
    public void testGroupByWithAggregationStringLiteralArguments() throws IOException {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);

        Merge distributedGroupByMerge = e.plan("select count('foo'), name from users group by name");
        RoutedCollectPhase collectPhase =
            ((RoutedCollectPhase) ((Collect) ((Merge) distributedGroupByMerge.subPlan()).subPlan()).collectPhase());
        Asserts.assertThat(collectPhase.toCollect()).satisfiesExactly(isReference("name"));
        GroupProjection groupProjection = (GroupProjection) collectPhase.projections().getFirst();
        Asserts.assertThat(groupProjection.values().getFirst()).isAggregation("count");
    }

    @Test
    public void testGroupByWithAggregationPlan() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan(
            "select count(*), name from users group by name");

        Merge reducerMerge = (Merge) distributedGroupByMerge.subPlan();

        // distributed collect
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) ((Collect) reducerMerge.subPlan()).collectPhase());
        assertThat(collectPhase.maxRowGranularity()).isEqualTo(RowGranularity.DOC);
        assertThat(collectPhase.nodeIds()).hasSize(2);
        assertThat(collectPhase.toCollect()).hasSize(1);
        assertThat(collectPhase.projections()).hasSize(1);
        assertThat(collectPhase.projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);
        assertThat(collectPhase.outputTypes()).hasSize(2);
        assertThat(collectPhase.outputTypes().get(0)).isEqualTo(DataTypes.STRING);
        assertThat(collectPhase.outputTypes().get(1)).isEqualTo(CountAggregation.LongStateType.INSTANCE);

        MergePhase mergePhase = reducerMerge.mergePhase();

        assertThat(mergePhase.numUpstreams()).isEqualTo(2);
        assertThat(mergePhase.nodeIds()).hasSize(2);
        assertThat(collectPhase.outputTypes()).isEqualTo(mergePhase.inputTypes());
        // for function evaluation and column-reordering there is always a EvalProjection
        assertThat(mergePhase.projections()).hasSize(2);
        assertThat(mergePhase.projections().get(1)).isExactlyInstanceOf(EvalProjection.class);

        assertThat(mergePhase.projections().get(0)).isExactlyInstanceOf(GroupProjection.class);
        GroupProjection groupProjection = (GroupProjection) mergePhase.projections().get(0);
        InputColumn inputColumn = (InputColumn) groupProjection.values().getFirst().inputs().getFirst();
        assertThat(inputColumn.index()).isEqualTo(1);

        assertThat(mergePhase.outputTypes()).hasSize(2);
        assertThat(mergePhase.outputTypes().get(0)).isEqualTo(DataTypes.LONG);
        assertThat(mergePhase.outputTypes().get(1)).isEqualTo(DataTypes.STRING);

        MergePhase localMerge = distributedGroupByMerge.mergePhase();

        assertThat(localMerge.numUpstreams()).isEqualTo(2);
        assertThat(localMerge.nodeIds()).hasSize(1);
        assertThat(localMerge.nodeIds().iterator().next()).isEqualTo(NODE_ID);
        assertThat(localMerge.inputTypes()).isEqualTo(mergePhase.outputTypes());

        assertThat(localMerge.projections()).isEmpty();
    }

    @Test
    public void testGroupByWithAggregationAndLimit() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan(
            "select count(*), name from users group by name limit 1 offset 1");

        Merge reducerMerge = (Merge) distributedGroupByMerge.subPlan();

        // distributed merge
        MergePhase distributedMergePhase = reducerMerge.mergePhase();
        assertThat(distributedMergePhase.projections().get(0)).isExactlyInstanceOf(GroupProjection.class);
        assertThat(distributedMergePhase.projections().get(1)).isExactlyInstanceOf(LimitAndOffsetProjection.class);

        // limit must include offset because the real limit can only be applied on the handler
        // after all rows have been gathered.
        LimitAndOffsetProjection projection = (LimitAndOffsetProjection) distributedMergePhase.projections().get(1);
        assertThat(projection.limit()).isEqualTo(2);
        assertThat(projection.offset()).isEqualTo(0);

        // local merge
        MergePhase localMergePhase = distributedGroupByMerge.mergePhase();
        assertThat(localMergePhase.projections().getFirst()).isExactlyInstanceOf(LimitAndOffsetProjection.class);
        projection = (LimitAndOffsetProjection) localMergePhase.projections().getFirst();
        assertThat(projection.limit()).isEqualTo(1);
        assertThat(projection.offset()).isEqualTo(1);
        assertThat(projection.outputs().get(0)).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) projection.outputs().get(0)).index()).isEqualTo(0);
        assertThat(projection.outputs().get(1)).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) projection.outputs().get(1)).index()).isEqualTo(1);
    }

    @Test
    public void testGroupByOnNodeLevel() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build();
        Collect collect = e.plan(
            "select count(*), name from sys.nodes group by name");

        assertThat(collect.resultDescription().nodeIds())
            .as("number of nodeIds must be 1, otherwise there must be a merge")
            .hasSize(1);

        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class));

        GroupProjection groupProjection = (GroupProjection) collectPhase.projections().get(0);

        assertThat(groupProjection.outputs()).satisfiesExactly(
            x -> assertThat(x).hasDataType(DataTypes.STRING),
            x -> assertThat(x).hasDataType(DataTypes.LONG)
        );

        assertThat(collectPhase.projections().get(1).outputs()).satisfiesExactly(
            x -> assertThat(x).hasDataType(DataTypes.LONG),
            x -> assertThat(x).hasDataType(DataTypes.STRING)
        );
    }

    @Test
    public void testNonDistributedGroupByOnClusteredColumn() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge merge = e.plan(
            "select count(*), id from users group by id limit 20");
        Collect collect = ((Collect) merge.subPlan());
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(LimitAndOffsetProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class) // swaps id, count(*) output from group by to count(*), id
        );
        assertThat(collectPhase.projections().getFirst().requiredGranularity()).isEqualTo(RowGranularity.SHARD);
        MergePhase mergePhase = merge.mergePhase();
        assertThat(mergePhase.projections()).satisfiesExactly(
            p -> assertThat(p).isExactlyInstanceOf(LimitAndOffsetProjection.class));
    }

    @Test
    public void testNonDistributedGroupByOnClusteredColumnSorted() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge merge = e.plan(
            "select count(*), id from users group by id order by 1 desc nulls last limit 20");
        Collect collect = ((Collect) merge.subPlan());
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        List<Projection> collectProjections = collectPhase.projections();
        assertThat(collectProjections).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(OrderedLimitAndOffsetProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class) // swap id, count(*) -> count(*), id
        );
        assertThat(collectProjections.get(1)).isExactlyInstanceOf(OrderedLimitAndOffsetProjection.class);
        assertThat(((OrderedLimitAndOffsetProjection) collectProjections.get(1)).orderBy()).hasSize(1);

        assertThat(collectProjections.get(0).requiredGranularity()).isEqualTo(RowGranularity.SHARD);
        MergePhase mergePhase = merge.mergePhase();
        assertThat(mergePhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(LimitAndOffsetProjection.class)
        );

        PositionalOrderBy positionalOrderBy = mergePhase.orderByPositions();
        assertThat(positionalOrderBy).isNotNull();
        assertThat(positionalOrderBy.indices().length).isEqualTo(1);
        assertThat(positionalOrderBy.indices()[0]).isEqualTo(0);
        assertThat(positionalOrderBy.reverseFlags()[0]).isTrue();
        assertThat(positionalOrderBy.nullsFirst()[0]).isFalse();
    }

    @Test
    public void testNonDistributedGroupByOnClusteredColumnSortedScalar() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge merge = e.plan(
            "select count(*) + 1, id from users group by id order by count(*) + 1 limit 20");
        Collect collect = (Collect) merge.subPlan();
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(OrderedLimitAndOffsetProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class)
        );
        assertThat(((OrderedLimitAndOffsetProjection) collectPhase.projections().get(1)).orderBy()).hasSize(1);

        assertThat(collectPhase.projections().get(0).requiredGranularity()).isEqualTo(RowGranularity.SHARD);
        MergePhase mergePhase = merge.mergePhase();
        assertThat(mergePhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(LimitAndOffsetProjection.class)
        );

        PositionalOrderBy positionalOrderBy = mergePhase.orderByPositions();
        assertThat(positionalOrderBy).isNotNull();
        assertThat(positionalOrderBy.indices().length).isEqualTo(1);
        assertThat(positionalOrderBy.indices()[0]).isEqualTo(0);
        assertThat(positionalOrderBy.reverseFlags()[0]).isFalse();
        assertThat(positionalOrderBy.nullsFirst()[0]).isFalse();
    }

    @Test
    public void testGroupByWithOrderOnAggregate() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge merge = e.plan(
            "select count(*), name from users group by name order by count(*)");

        assertThat(merge.mergePhase().orderByPositions()).isNotNull();

        Merge reducerMerge = (Merge) merge.subPlan();
        MergePhase mergePhase = reducerMerge.mergePhase();

        assertThat(mergePhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(OrderedLimitAndOffsetProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class)
        );
        OrderedLimitAndOffsetProjection projection = (OrderedLimitAndOffsetProjection) mergePhase.projections().get(1);
        Symbol orderBy = projection.orderBy().getFirst();
        assertThat(orderBy).isExactlyInstanceOf(InputColumn.class);

        assertThat(orderBy.valueType()).isEqualTo(DataTypes.LONG);
    }

    @Test
    public void testHandlerSideRoutingGroupBy() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build();
        Collect collect = e.plan(
            "select count(*) from sys.cluster group by name");
        // just testing the dispatching here.. making sure it is not a ESSearchNode
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        assertThat(collectPhase.toCollect().getFirst()).isInstanceOf(Reference.class);
        assertThat(collectPhase.toCollect()).hasSize(1);

        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class)
        );
    }

    @Test
    public void testCountDistinctWithGroupBy() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan(
            "select count(distinct id), name from users group by name order by count(distinct id)");
        Merge reducerMerge = (Merge) distributedGroupByMerge.subPlan();
        CollectPhase collectPhase = ((Collect) reducerMerge.subPlan()).collectPhase();

        // collect
        assertThat(collectPhase.toCollect().getFirst()).isInstanceOf(Reference.class);
        assertThat(collectPhase.toCollect()).hasSize(2);
        assertThat(((Reference) collectPhase.toCollect().get(0)).column().name()).isEqualTo("id");
        assertThat(((Reference) collectPhase.toCollect().get(1)).column().name()).isEqualTo("name");
        Projection projection = collectPhase.projections().getFirst();
        assertThat(projection).isExactlyInstanceOf(GroupProjection.class);
        GroupProjection groupProjection = (GroupProjection) projection;
        Symbol groupKey = groupProjection.keys().getFirst();
        assertThat(groupKey).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) groupKey).index()).isEqualTo(1);
        assertThat(groupProjection.values()).hasSize(1);
        assertThat(groupProjection.mode()).isEqualTo(AggregateMode.ITER_PARTIAL);

        Aggregation aggregation = groupProjection.values().getFirst();
        Symbol aggregationInput = aggregation.inputs().getFirst();
        assertThat(aggregationInput.symbolType()).isEqualTo(SymbolType.INPUT_COLUMN);


        // reducer
        MergePhase mergePhase = reducerMerge.mergePhase();
        assertThat(mergePhase.projections()).satisfiesExactly(
            p -> assertThat(p).isExactlyInstanceOf(GroupProjection.class),
            p -> assertThat(p).isExactlyInstanceOf(OrderedLimitAndOffsetProjection.class),
            p -> assertThat(p).isExactlyInstanceOf(EvalProjection.class));

        Projection groupProjection1 = mergePhase.projections().getFirst();
        groupProjection = (GroupProjection) groupProjection1;
        assertThat(groupProjection.keys().getFirst()).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) groupProjection.keys().getFirst()).index()).isEqualTo(0);
        assertThat(groupProjection.mode()).isEqualTo(AggregateMode.PARTIAL_FINAL);
        assertThat(groupProjection.values().getFirst()).isExactlyInstanceOf(Aggregation.class);

        OrderedLimitAndOffsetProjection limitAndOffsetProjection =
            (OrderedLimitAndOffsetProjection) mergePhase.projections().get(1);
        Symbol collection_count = limitAndOffsetProjection.outputs().getFirst();
        Asserts.assertThat(collection_count).isInputColumn(0);

        // handler
        MergePhase localMergeNode = distributedGroupByMerge.mergePhase();
        assertThat(localMergeNode.projections()).isEmpty();
    }

    @Test
    public void testGroupByHavingNonDistributed() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge merge = e.plan(
            "select id from users group by id having id > 0");
        Collect collect = (Collect) merge.subPlan();
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        Asserts.assertThat(collectPhase.where()).isSQL("(doc.users.id > 0::bigint)");
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class)
        );

        MergePhase localMergeNode = merge.mergePhase();
        assertThat(localMergeNode.projections()).isEmpty();
    }

    @Test
    public void testGroupByWithHavingAndLimit() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge planNode = e.plan(
            "select count(*), name from users group by name having count(*) > 1 limit 100");
        Merge reducerMerge = (Merge) planNode.subPlan();
        MergePhase mergePhase = reducerMerge.mergePhase(); // reducer

        Projection projection = mergePhase.projections().get(1);
        assertThat(projection).isExactlyInstanceOf(FilterProjection.class);
        FilterProjection filterProjection = (FilterProjection) projection;

        Symbol countArgument = ((Function) filterProjection.query()).arguments().getFirst();
        assertThat(countArgument).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) countArgument).index()).isEqualTo(1);  // pointing to second output from group projection

        // outputs: name, count(*)
        LimitAndOffsetProjection limitAndOffsetProjection = (LimitAndOffsetProjection) mergePhase.projections().get(2);
        assertThat(limitAndOffsetProjection.outputs().get(0).valueType()).isEqualTo(DataTypes.STRING);
        assertThat(limitAndOffsetProjection.outputs().get(1).valueType()).isEqualTo(DataTypes.LONG);


        MergePhase localMerge = planNode.mergePhase();
        // limitAndOffset projection
        //      outputs: count(*), name
        limitAndOffsetProjection = (LimitAndOffsetProjection) localMerge.projections().getFirst();
        assertThat(limitAndOffsetProjection.outputs().get(0).valueType()).isEqualTo(DataTypes.LONG);
        assertThat(limitAndOffsetProjection.outputs().get(1).valueType()).isEqualTo(DataTypes.STRING);
    }

    @Test
    public void testGroupByWithHavingAndNoLimit() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge planNode = e.plan(
            "select count(*), name from users group by name having count(*) > 1");
        Merge reducerMerge = (Merge) planNode.subPlan();
        MergePhase mergePhase = reducerMerge.mergePhase(); // reducer

        // group projection
        //      outputs: name, count(*)

        Projection projection = mergePhase.projections().get(1);
        assertThat(projection).isExactlyInstanceOf(FilterProjection.class);
        FilterProjection filterProjection = (FilterProjection) projection;

        Symbol countArgument = ((Function) filterProjection.query()).arguments().getFirst();
        assertThat(countArgument).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) countArgument).index()).isEqualTo(1);  // pointing to second output from group projection

        assertThat(mergePhase.outputTypes().get(0)).isEqualTo(DataTypes.LONG);
        assertThat(mergePhase.outputTypes().get(1)).isEqualTo(DataTypes.STRING);

        mergePhase = planNode.mergePhase();

        assertThat(mergePhase.outputTypes().get(0)).isEqualTo(DataTypes.LONG);
        assertThat(mergePhase.outputTypes().get(1)).isEqualTo(DataTypes.STRING);
    }

    @Test
    public void testGroupByWithHavingAndNoSelectListReordering() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge planNode = e.plan(
            "select name, count(*) from users group by name having count(*) > 1");
        Merge reducerMerge = (Merge) planNode.subPlan();

        MergePhase reduceMergePhase = reducerMerge.mergePhase();

        // group projection
        //      outputs: name, count(*)
        // filter projection
        //      outputs: name, count(*)

        assertThat(reduceMergePhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(FilterProjection.class)
        );
        Projection projection = reduceMergePhase.projections().get(1);
        assertThat(projection).isExactlyInstanceOf(FilterProjection.class);
        FilterProjection filterProjection = (FilterProjection) projection;

        Symbol countArgument = ((Function) filterProjection.query()).arguments().getFirst();
        assertThat(countArgument).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) countArgument).index()).isEqualTo(1);  // pointing to second output from group projection

        // outputs: name, count(*)
        assertThat(((InputColumn) filterProjection.outputs().get(0)).index()).isEqualTo(0);
        assertThat(((InputColumn) filterProjection.outputs().get(1)).index()).isEqualTo(1);

        MergePhase localMerge = planNode.mergePhase();
        assertThat(localMerge.projections()).isEmpty();
    }

    @Test
    public void testGroupByHavingAndNoSelectListReOrderingWithLimit() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge planNode = e.plan(
            "select name, count(*) from users group by name having count(*) > 1 limit 100");
        Merge reducerMerge = (Merge) planNode.subPlan();

        MergePhase reducePhase = reducerMerge.mergePhase();

        // group projection
        //      outputs: name, count(*)
        // filter projection
        //      outputs: name, count(*)
        // limitAndOffset projection
        //      outputs: name, count(*)

        Projection projection = reducePhase.projections().get(1);
        assertThat(projection).isExactlyInstanceOf(FilterProjection.class);
        FilterProjection filterProjection = (FilterProjection) projection;

        Symbol countArgument = ((Function) filterProjection.query()).arguments().getFirst();
        assertThat(countArgument).isExactlyInstanceOf(InputColumn.class);
        assertThat(((InputColumn) countArgument).index()).isEqualTo(1);  // pointing to second output from group projection

        // outputs: name, count(*)
        assertThat(((InputColumn) filterProjection.outputs().get(0)).index()).isEqualTo(0);
        assertThat(((InputColumn) filterProjection.outputs().get(1)).index()).isEqualTo(1);

        // outputs: name, count(*)
        LimitAndOffsetProjection limitAndOffsetProjection = (LimitAndOffsetProjection) reducePhase.projections().get(2);
        assertThat(((InputColumn) limitAndOffsetProjection.outputs().get(0)).index()).isEqualTo(0);
        assertThat(((InputColumn) limitAndOffsetProjection.outputs().get(1)).index()).isEqualTo(1);


        MergePhase localMerge = planNode.mergePhase();

        // limitAndOffset projection
        //      outputs: name, count(*)
        limitAndOffsetProjection = (LimitAndOffsetProjection) localMerge.projections().getFirst();
        assertThat(((InputColumn) limitAndOffsetProjection.outputs().get(0)).index()).isEqualTo(0);
        assertThat(((InputColumn) limitAndOffsetProjection.outputs().get(1)).index()).isEqualTo(1);
    }

    @Test
    public void testDistributedGroupByProjectionHasShardLevelGranularity() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan("select count(*) from users group by name");
        Merge reduceMerge = (Merge) distributedGroupByMerge.subPlan();
        CollectPhase collectPhase = ((Collect) reduceMerge.subPlan()).collectPhase();
        assertThat(collectPhase.projections()).hasSize(1);
        assertThat(collectPhase.projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);
        assertThat(collectPhase.projections().getFirst().requiredGranularity()).isEqualTo(RowGranularity.SHARD);
    }

    @Test
    public void testNonDistributedGroupByProjectionHasShardLevelGranularity() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan("select count(distinct id), name from users" +
                                               " group by name order by count(distinct id)");
        Merge reduceMerge = (Merge) distributedGroupByMerge.subPlan();
        CollectPhase collectPhase = ((Collect) reduceMerge.subPlan()).collectPhase();
        assertThat(collectPhase.projections()).hasSize(1);
        assertThat(collectPhase.projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);
        assertThat(collectPhase.projections().getFirst().requiredGranularity()).isEqualTo(RowGranularity.SHARD);
    }

    @Test
    public void testNoDistributedGroupByOnAllPrimaryKeys() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(
                "create table doc.empty_parted (" +
                "   id integer primary key," +
                "   date timestamp with time zone primary key" +
                ") clustered by (id) partitioned by (date)"
            );
        Collect collect = e.plan(
            "select count(*), id, date from empty_parted group by id, date limit 20");
        RoutedCollectPhase collectPhase = ((RoutedCollectPhase) collect.collectPhase());
        assertThat(collectPhase.projections()).hasSize(3);
        assertThat(collectPhase.projections().get(0)).isExactlyInstanceOf(GroupProjection.class);
        assertThat(collectPhase.projections().get(0).requiredGranularity()).isEqualTo(RowGranularity.SHARD);
        assertThat(collectPhase.projections().get(1)).isExactlyInstanceOf(LimitAndOffsetProjection.class);
        assertThat(collectPhase.projections().get(2)).isExactlyInstanceOf(EvalProjection.class);
    }

    @Test
    public void testNonDistributedGroupByAggregationsWrappedInScalar() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(
                "create table doc.empty_parted (" +
                "   id integer primary key," +
                "   date timestamp with time zone primary key" +
                ") clustered by (id) partitioned by (date)"
            );
        Collect collect = e.plan(
            "select (count(*) + 1), id from empty_parted group by id");

        CollectPhase collectPhase = collect.collectPhase();
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class), // shard level
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class), // node level
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class) // count(*) + 1
        );
    }

    @Test
    public void testGroupByHaving() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(TableDefinitions.USER_TABLE_DEFINITION);
        Merge distributedGroupByMerge = e.plan(
            "select avg(date), name from users group by name having min(date) > '1970-01-01'");
        Merge reduceMerge = (Merge) distributedGroupByMerge.subPlan();
        CollectPhase collectPhase = ((Collect) reduceMerge.subPlan()).collectPhase();
        assertThat(collectPhase.projections()).hasSize(1);
        assertThat(collectPhase.projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);

        MergePhase reducePhase = reduceMerge.mergePhase();

        assertThat(reducePhase.projections()).hasSize(3);

        // grouping
        assertThat(reducePhase.projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);
        GroupProjection groupProjection = (GroupProjection) reducePhase.projections().get(0);
        assertThat(groupProjection.values()).hasSize(2);

        // filter the having clause
        assertThat(reducePhase.projections().get(1)).isExactlyInstanceOf(FilterProjection.class);

        assertThat(reducePhase.projections().get(2)).isExactlyInstanceOf(EvalProjection.class);
        EvalProjection eval = (EvalProjection) reducePhase.projections().get(2);
        assertThat(eval.outputs().get(0).valueType()).isEqualTo(DataTypes.DOUBLE);
        assertThat(eval.outputs().get(1).valueType()).isEqualTo(DataTypes.STRING);
    }

    @Test
    public void testNestedGroupByAggregation() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build();
        Collect collect = e.plan("select count(*) from (" +
                                 "  select max(load['1']) as maxLoad, hostname " +
                                 "  from sys.nodes " +
                                 "  group by hostname having max(load['1']) > 50) as nodes " +
                                 "group by hostname");
        assertThat(collect.nodeIds())
            .as("would require merge if more than 1 nodeIds")
            .hasSize(1);

        CollectPhase collectPhase = collect.collectPhase();
        assertThat(collectPhase.projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(FilterProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class)
        );
        Projection firstGroupProjection = collectPhase.projections().get(0);
        assertThat(((GroupProjection) firstGroupProjection).mode()).isEqualTo(AggregateMode.ITER_FINAL);

        Projection secondGroupProjection = collectPhase.projections().get(3);
        assertThat(((GroupProjection) secondGroupProjection).mode()).isEqualTo(AggregateMode.ITER_FINAL);
    }

    @Test
    public void testGroupByOnClusteredByColumnPartitionedOnePartition() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(
                "create table doc.clustered_parted (" +
                "   id integer," +
                "   date timestamp with time zone," +
                "   city string" +
                ") clustered by (city) partitioned by (date) ",
                new PartitionName(new RelationName("doc", "clustered_parted"), singletonList("1395874800000")).asIndexName(),
                new PartitionName(new RelationName("doc", "clustered_parted"), singletonList("1395961200000")).asIndexName()
            );

        // only one partition hit
        Merge optimizedPlan = e.plan("select count(*), city from clustered_parted where date=1395874800000 group by city");
        Collect collect = (Collect) optimizedPlan.subPlan();

        assertThat(collect.collectPhase().projections()).satisfiesExactly(
            x -> assertThat(x).isExactlyInstanceOf(GroupProjection.class),
            x -> assertThat(x).isExactlyInstanceOf(EvalProjection.class));
        assertThat(collect.collectPhase().projections().getFirst()).isExactlyInstanceOf(GroupProjection.class);

        assertThat(optimizedPlan.mergePhase().projections()).hasSize(0);

        // > 1 partition hit
        ExecutionPlan executionPlan = e.plan("select count(*), city from clustered_parted where date=1395874800000 or date=1395961200000 group by city");
        assertThat(executionPlan).isExactlyInstanceOf(Merge.class);
        assertThat(((Merge) executionPlan).subPlan()).isExactlyInstanceOf(Merge.class);
    }

    @Test
    public void testGroupByOrderByPartitionedClolumn() throws Exception {
        var e = SQLExecutor.builder(clusterService)
            .setNumNodes(2)
            .build()
            .addTable(
                "create table doc.clustered_parted (" +
                "   id integer," +
                "   date timestamp with time zone," +
                "   city string" +
                ") clustered by (city) partitioned by (date) ",
                new PartitionName(new RelationName("doc", "clustered_parted"), singletonList("1395874800000")).asIndexName(),
                new PartitionName(new RelationName("doc", "clustered_parted"), singletonList("1395961200000")).asIndexName()
            );
        Merge plan = e.plan("select date from clustered_parted group by date order by date");
        Merge reduceMerge = (Merge) plan.subPlan();
        OrderedLimitAndOffsetProjection limitAndOffsetProjection =
            (OrderedLimitAndOffsetProjection)reduceMerge.mergePhase().projections().get(1);

        Symbol orderBy = limitAndOffsetProjection.orderBy().getFirst();
        assertThat(orderBy).isExactlyInstanceOf(InputColumn.class);
        assertThat(orderBy.valueType()).isEqualTo(DataTypes.TIMESTAMPZ);
    }
}

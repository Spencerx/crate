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

package io.crate.execution.engine.collect;

import static io.crate.operation.aggregation.AggregationTestCase.closeShard;
import static io.crate.operation.aggregation.AggregationTestCase.createCollectPhase;
import static io.crate.operation.aggregation.AggregationTestCase.createCollectTask;
import static io.crate.operation.aggregation.AggregationTestCase.newStartedPrimaryShard;
import static io.crate.testing.TestingHelpers.createNodeContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsearch.cluster.metadata.Metadata.COLUMN_OID_UNASSIGNED;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.elasticsearch.Version;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.index.shard.IndexShard;
import org.junit.Before;
import org.junit.Test;

import io.crate.data.BatchIterator;
import io.crate.data.Row;
import io.crate.data.breaker.RamAccounting;
import io.crate.data.testing.TestingRowConsumer;
import io.crate.exceptions.JobKilledException;
import io.crate.execution.dsl.projection.GroupProjection;
import io.crate.execution.engine.aggregation.impl.SumAggregation;
import io.crate.execution.engine.fetch.ReaderContext;
import io.crate.expression.reference.doc.lucene.CollectorContext;
import io.crate.expression.reference.doc.lucene.LongColumnReference;
import io.crate.expression.reference.doc.lucene.LuceneCollectorExpression;
import io.crate.expression.reference.doc.lucene.LuceneReferenceResolver;
import io.crate.expression.reference.doc.lucene.StringColumnReference;
import io.crate.expression.symbol.AggregateMode;
import io.crate.expression.symbol.InputColumn;
import io.crate.lucene.LuceneQueryBuilder;
import io.crate.metadata.FunctionType;
import io.crate.metadata.Functions;
import io.crate.metadata.IndexType;
import io.crate.metadata.PartitionName;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.SimpleReference;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.functions.Signature;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.TestingHelpers;
import io.crate.types.DataTypes;

public class DocValuesGroupByOptimizedIteratorTest extends CrateDummyClusterServiceUnitTest {

    private Functions functions;
    private IndexSearcher indexSearcher;

    private final List<Object[]> rows = List.of(
        new Object[]{"1", 1L, 1L},
        new Object[]{"0", 0L, 2L},
        new Object[]{"1", 1L, 3L},
        new Object[]{"0", 0L, 4L}
    );

    @Before
    public void setup() throws IOException {
        var nodeContext = createNodeContext();
        functions = nodeContext.functions();

        var indexWriter = new IndexWriter(new ByteBuffersDirectory(), new IndexWriterConfig());
        for (var row : rows) {
            Document doc = new Document();
            doc.add(new SortedSetDocValuesField("x", BytesRefs.toBytesRef(row[0])));
            doc.add(new NumericDocValuesField("y", (Long) row[1]));
            doc.add(new NumericDocValuesField("z", (Long) row[2]));
            indexWriter.addDocument(doc);
        }
        indexWriter.commit();
        indexSearcher = new IndexSearcher(DirectoryReader.open(indexWriter));
    }

    @Test
    public void test_group_by_doc_values_optimized_iterator_for_single_numeric_key() throws Exception {
        SumAggregation<?> sumAggregation = (SumAggregation<?>) functions.getQualified(
            Signature.builder(SumAggregation.NAME, FunctionType.AGGREGATE)
                .argumentTypes(DataTypes.LONG.getTypeSignature())
                .returnType(DataTypes.LONG.getTypeSignature())
                .build(),
            List.of(DataTypes.LONG),
            DataTypes.LONG
        );

        var sumDocValuesAggregator = sumAggregation.getDocValueAggregator(
            mock(LuceneReferenceResolver.class),
            List.of(new SimpleReference(
                new ReferenceIdent(RelationName.fromIndexName("test"), "z"),
                RowGranularity.DOC,
                DataTypes.LONG,
                IndexType.PLAIN,
                true,
                true,
                0,
                COLUMN_OID_UNASSIGNED,
                false,
                null)
            ),
            mock(DocTableInfo.class),
            Version.CURRENT,
            List.of()
        );
        var keyExpressions = List.of(new LongColumnReference("y"));
        var it = DocValuesGroupByOptimizedIterator.GroupByIterator.forSingleKey(
            List.of(sumDocValuesAggregator),
            indexSearcher,
            new SimpleReference(
                new ReferenceIdent(RelationName.fromIndexName("test"), "y"),
                RowGranularity.DOC,
                DataTypes.LONG,
                IndexType.PLAIN,
                true,
                true,
                0,
                COLUMN_OID_UNASSIGNED,
                false,
                null
            ),
            keyExpressions,
            RamAccounting.NO_ACCOUNTING,
            null,
            null,
            new MatchAllDocsQuery(),
            new CollectorContext(() -> null)
        );

        var rowConsumer = new TestingRowConsumer();
        rowConsumer.accept(it, null);
        assertThat(rowConsumer.getResult()).containsExactlyInAnyOrder(
            new Object[]{0L, 6L}, new Object[]{1L, 4L});
    }

    @Test
    public void test_group_by_doc_values_optimized_iterator_for_many_keys() throws Exception {
        SumAggregation<?> sumAggregation = (SumAggregation<?>) functions.getQualified(
            Signature.builder(SumAggregation.NAME, FunctionType.AGGREGATE)
                .argumentTypes(DataTypes.LONG.getTypeSignature())
                .returnType(DataTypes.LONG.getTypeSignature())
                .build(),
            List.of(DataTypes.LONG),
            DataTypes.LONG
        );

        var sumDocValuesAggregator = sumAggregation.getDocValueAggregator(
            mock(LuceneReferenceResolver.class),
            List.of(new SimpleReference(
                new ReferenceIdent(
                    RelationName.fromIndexName("test"),
                    "z"),
                RowGranularity.DOC,
                DataTypes.LONG,
                IndexType.PLAIN,
                true,
                true,
                0,
                COLUMN_OID_UNASSIGNED,
                false,
                null)
            ),
            mock(DocTableInfo.class),
            Version.CURRENT,
            List.of()
        );
        var keyExpressions = List.of(new StringColumnReference("x"), new LongColumnReference("y"));
        var keyRefs = List.<Reference>of(
            new SimpleReference(
                new ReferenceIdent(RelationName.fromIndexName("test"), "x"),
                RowGranularity.DOC,
                DataTypes.STRING,
                IndexType.PLAIN,
                true,
                true,
                1,
                111,
                false,
                null
            ),
            new SimpleReference(
                new ReferenceIdent(RelationName.fromIndexName("test"), "y"),
                RowGranularity.DOC,
                DataTypes.LONG,
                IndexType.PLAIN,
                true,
                true,
                2,
                111,
                false,
                null
            )
        );
        var it = DocValuesGroupByOptimizedIterator.GroupByIterator.forManyKeys(
            List.of(sumDocValuesAggregator),
            indexSearcher,
            keyRefs,
            keyExpressions,
            RamAccounting.NO_ACCOUNTING,
            null,
            null,
            new MatchAllDocsQuery(),
            new CollectorContext(() -> null)
        );

        var rowConsumer = new TestingRowConsumer();
        rowConsumer.accept(it, null);

        assertThat(rowConsumer.getResult()).containsExactlyInAnyOrder(
            new Object[]{"0", 0L, 6L}, new Object[]{"1", 1L, 4L});
    }

    @Test
    public void test_create_optimized_iterator_for_single_string_key() throws Exception {
        GroupProjection groupProjection = new GroupProjection(
            List.of(new InputColumn(0, DataTypes.STRING)),
            List.of(),
            AggregateMode.ITER_PARTIAL,
            RowGranularity.SHARD
        );
        PartitionName partitionName = new PartitionName(new RelationName("doc", "test"), List.of());
        var reference = new SimpleReference(
            new ReferenceIdent(partitionName.relationName(), "x"),
            RowGranularity.DOC,
            DataTypes.STRING,
            IndexType.PLAIN,
            true,
            true,
            0,
            111,
            false,
            null
        );
        IndexShard shard = newStartedPrimaryShard(
            TestingHelpers.createNodeContext(),
            List.of(reference),
            THREAD_POOL
        );
        var collectPhase = createCollectPhase(List.of(reference), List.of(groupProjection));
        var collectTask = createCollectTask(shard, collectPhase, Version.CURRENT);
        var nodeCtx = createNodeContext();
        var referenceResolver = new LuceneReferenceResolver(partitionName.values(), List.of(), List.of(), Version.CURRENT, _ -> false);

        var it = DocValuesGroupByOptimizedIterator.tryOptimize(
            functions,
            referenceResolver,
            shard,
            mock(DocTableInfo.class),
            partitionName.values(),
            new LuceneQueryBuilder(nodeCtx),
            new DocInputFactory(
                nodeCtx,
                referenceResolver
            ),
            collectPhase,
            collectTask
        );
        assertThat(it).isNotNull();

        collectTask.kill(JobKilledException.of(null));
        closeShard(shard);
    }

    @Test
    public void test_optimized_iterator_stop_processing_on_kill() throws Exception {
        Throwable expectedException = stopOnInterrupting(it -> it.kill(new InterruptedException("killed")));
        assertThat(expectedException).isExactlyInstanceOf(InterruptedException.class);
    }

    @Test
    public void test_optimized_iterator_stop_processing_on_close() throws Exception {
        Throwable expectedException = stopOnInterrupting(BatchIterator::close);
        assertThat(expectedException).isExactlyInstanceOf(IllegalStateException.class);
    }

    private Throwable stopOnInterrupting(Consumer<BatchIterator<Row>> interrupt) throws Exception {
        CountDownLatch waitForLoadNextBatch = new CountDownLatch(1);
        CountDownLatch pauseOnDocumentCollecting = new CountDownLatch(1);
        CountDownLatch batchLoadingCompleted = new CountDownLatch(1);

        BatchIterator<Row> it = createBatchIterator(() -> {
            waitForLoadNextBatch.countDown();
            try {
                pauseOnDocumentCollecting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        AtomicReference<Throwable> exception = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                it.loadNextBatch().whenComplete((r, e) -> {
                    if (e != null) {
                        exception.set(e.getCause());
                    }
                    batchLoadingCompleted.countDown();
                });
            } catch (Exception e) {
                exception.set(e);
            }
        });
        t.start();
        waitForLoadNextBatch.await(5, TimeUnit.SECONDS);
        interrupt.accept(it);
        pauseOnDocumentCollecting.countDown();
        batchLoadingCompleted.await(5, TimeUnit.SECONDS);
        return exception.get();
    }

    private BatchIterator<Row> createBatchIterator(Runnable onNextReader) {
        return DocValuesGroupByOptimizedIterator.GroupByIterator.getIterator(
            List.of(),
            indexSearcher,
            List.of(new LuceneCollectorExpression<>() {

                @Override
                public void setNextReader(ReaderContext context) throws IOException {
                    onNextReader.run();
                }

                @Override
                public Object value() {
                    return null;
                }
            }),
            null,
            null,
            null,
            (states, key) -> {
            },
            (expressions) -> expressions.get(0).value(),
            (key, cells) -> cells[0] = key,
            new MatchAllDocsQuery(),
            new CollectorContext(() -> null)
        );
    }
}

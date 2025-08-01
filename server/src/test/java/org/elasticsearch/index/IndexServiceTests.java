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

package org.elasticsearch.index;

import static io.crate.protocols.postgres.PGErrorStatus.INTERNAL_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.elasticsearch.index.shard.IndexShardTestCase.flushShard;
import static org.elasticsearch.index.shard.IndexShardTestCase.getEngine;
import static org.elasticsearch.test.InternalSettingsPlugin.TRANSLOG_RETENTION_CHECK_INTERVAL_SETTING;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineTestCase;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.IntegTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Test;

import io.crate.common.unit.TimeValue;
import io.crate.execution.dml.TranslogIndexer;
import io.crate.metadata.IndexName;
import io.crate.testing.Asserts;

@IntegTestCase.ClusterScope(numDataNodes = 1, supportsDedicatedMasters = false)
public class IndexServiceTests extends IntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        var plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(InternalSettingsPlugin.class);
        return plugins;
    }

    public void testBaseAsyncTask() throws Exception {
        execute("create table test (x int) clustered into 1 shards");
        IndexService indexService = getIndexService("test");

        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> latch2 = new AtomicReference<>(new CountDownLatch(1));
        final AtomicInteger count = new AtomicInteger();
        IndexService.BaseAsyncTask task = new IndexService.BaseAsyncTask(indexService, TimeValue.timeValueMillis(1)) {
            @Override
            protected void runInternal() {
                final CountDownLatch l1 = latch.get();
                final CountDownLatch l2 = latch2.get();
                count.incrementAndGet();
                assertThat(Thread.currentThread().getName().contains("[generic]")).as("generic threadpool is configured").isTrue();
                l1.countDown();
                try {
                    l2.await();
                } catch (InterruptedException e) {
                    fail("interrupted");
                }
                if (randomBoolean()) { // task can throw exceptions!!
                    if (randomBoolean()) {
                        throw new RuntimeException("foo");
                    } else {
                        throw new RuntimeException("bar");
                    }
                }
            }

            @Override
            protected String getThreadPool() {
                return ThreadPool.Names.GENERIC;
            }
        };

        latch.get().await();
        latch.set(new CountDownLatch(1));
        assertThat(count.get()).isEqualTo(1);
        // here we need to swap first before we let it go otherwise threads might be very fast and run that task twice due to
        // random exception and the schedule interval is 1ms
        latch2.getAndSet(new CountDownLatch(1)).countDown();
        latch.get().await();
        assertThat(count.get()).isEqualTo(2);
        task.close();
        latch2.get().countDown();
        assertThat(count.get()).isEqualTo(2);

        task = new IndexService.BaseAsyncTask(indexService, TimeValue.timeValueMillis(1000000)) {
            @Override
            protected void runInternal() {

            }
        };
        assertThat(task.mustReschedule()).isTrue();

        // now close the index
        execute("alter table test close");
        final Index index = indexService.index();
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());
        final IndexService closedIndexService = getIndicesService().indexServiceSafe(index);
        assertThat(closedIndexService).isNotSameAs(indexService);
        assertThat(task.mustReschedule()).isFalse();
        assertThat(task.isClosed()).isFalse();
        assertThat(task.getInterval().millis()).isEqualTo(1000000);

        assertThat(closedIndexService).isNotSameAs(indexService);
        assertThat(task.mustReschedule()).isFalse();
        assertThat(task.isClosed()).isFalse();
        assertThat(task.getInterval().millis()).isEqualTo(1000000);

        // now reopen the index
        execute("alter table test open");
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());
        indexService = getIndicesService().indexServiceSafe(index);
        assertThat(indexService).isNotSameAs(closedIndexService);

        task = new IndexService.BaseAsyncTask(indexService, TimeValue.timeValueMillis(100000)) {
            @Override
            protected void runInternal() {

            }
        };
        assertThat(task.mustReschedule()).isTrue();
        assertThat(task.isClosed()).isFalse();
        assertThat(task.isScheduled()).isTrue();

        indexService.close("simon says", false);
        assertThat(task.mustReschedule()).as("no shards left").isFalse();
        assertThat(task.isScheduled()).isTrue();
        task.close();
        assertThat(task.isScheduled()).isFalse();
    }

    @Test
    public void testRefreshTaskIsUpdated() throws Exception {
        execute("create table test (x int) clustered into 1 shards");
        IndexService indexService = getIndexService("test");
        IndexService.AsyncRefreshTask refreshTask = indexService.getRefreshTask();
        assertThat(refreshTask.getInterval().millis()).isEqualTo(1000);
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();

        // now disable
        execute("alter table test set (refresh_interval = -1)");
        assertThat(indexService.getRefreshTask()).isNotSameAs(refreshTask);
        assertThat(refreshTask.isClosed()).isTrue();
        assertThat(refreshTask.isScheduled()).isFalse();

        execute("alter table test set (refresh_interval = '100ms')");
        assertThat(indexService.getRefreshTask()).isNotSameAs(refreshTask);
        assertThat(refreshTask.isClosed()).isTrue();

        refreshTask = indexService.getRefreshTask();
        assertThat(refreshTask.mustReschedule()).isTrue();
        assertThat(refreshTask.isScheduled()).isTrue();
        assertThat(refreshTask.getInterval().millis()).isEqualTo(100);

        execute("alter table test set (refresh_interval = '200ms')");
        assertThat(indexService.getRefreshTask()).isNotSameAs(refreshTask);
        assertThat(refreshTask.isClosed()).isTrue();

        refreshTask = indexService.getRefreshTask();
        assertThat(refreshTask.mustReschedule()).isTrue();
        assertThat(refreshTask.isScheduled()).isTrue();
        assertThat(refreshTask.getInterval().millis()).isEqualTo(200);

        // set it to 200ms again
        execute("alter table test set (refresh_interval = '200ms')");
        assertThat(indexService.getRefreshTask()).isSameAs(refreshTask);
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();
        assertThat(refreshTask.isScheduled()).isTrue();
        assertThat(refreshTask.isClosed()).isFalse();
        assertThat(refreshTask.getInterval().millis()).isEqualTo(200);

        // now close the index
        execute("alter table test close");
        final Index index = indexService.index();
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());

        final IndexService closedIndexService = getIndicesService().indexServiceSafe(index);
        assertThat(closedIndexService).isNotSameAs(indexService);
        assertThat(closedIndexService.getRefreshTask()).isNotSameAs(refreshTask);
        assertThat(closedIndexService.getRefreshTask().mustReschedule()).isFalse();
        assertThat(closedIndexService.getRefreshTask().isClosed()).isFalse();
        assertThat(closedIndexService.getRefreshTask().getInterval().millis()).isEqualTo(200);

        // now reopen the index
        execute("alter table test open");
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());
        indexService = getIndicesService().indexServiceSafe(index);
        assertThat(indexService).isNotSameAs(closedIndexService);
        refreshTask = indexService.getRefreshTask();
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();
        assertThat(refreshTask.isScheduled()).isTrue();
        assertThat(refreshTask.isClosed()).isFalse();

        indexService.close("simon says", false);
        assertThat(refreshTask.isScheduled()).isFalse();
        assertThat(refreshTask.isClosed()).isTrue();
    }

    public void testFsyncTaskIsRunning() throws Exception {
        execute("create table test(x int) clustered into 1 shards with (\"translog.durability\" = 'ASYNC')");
        IndexService indexService = getIndexService("test");
        IndexService.AsyncTranslogFSync fsyncTask = indexService.getFsyncTask();
        assertThat(fsyncTask).isNotNull();
        assertThat(fsyncTask.getInterval().millis()).isEqualTo(5000);
        assertThat(fsyncTask.mustReschedule()).isTrue();
        assertThat(fsyncTask.isScheduled()).isTrue();

        // now close the index
        execute("alter table test close");
        final Index index = indexService.index();
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());

        final IndexService closedIndexService = getIndicesService().indexServiceSafe(index);
        assertThat(closedIndexService).isNotSameAs(indexService);
        assertThat(closedIndexService.getFsyncTask()).isNotSameAs(fsyncTask);
        assertThat(closedIndexService.getFsyncTask().mustReschedule()).isFalse();
        assertThat(closedIndexService.getFsyncTask().isClosed()).isFalse();
        assertThat(closedIndexService.getFsyncTask().getInterval().millis()).isEqualTo(5000);

        // now reopen the index
        execute("alter table test open");
        assertBusy(() -> assertThat(getIndicesService().hasIndex(index))
            .as("Index not found: " + index.getName())
            .isTrue());
        indexService = getIndicesService().indexServiceSafe(index);
        assertThat(indexService).isNotSameAs(closedIndexService);
        fsyncTask = indexService.getFsyncTask();
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();
        assertThat(fsyncTask.isScheduled()).isTrue();
        assertThat(fsyncTask.isClosed()).isFalse();

        indexService.close("simon says", false);
        assertThat(fsyncTask.isScheduled()).isFalse();
        assertThat(fsyncTask.isClosed()).isTrue();

        execute("create table test1 (x int, data text)");
        indexService = getIndexService("test1");
        assertThat(indexService.getFsyncTask()).isNull();
    }

    @Test
    public void testRefreshActuallyWorks() throws Exception {
        execute("create table test (x int, data text) clustered into 1 shards");
        var indexService = getIndexService("test");
        var indexName = indexService.index().getName();
        ensureGreen();
        IndexService.AsyncRefreshTask refreshTask = indexService.getRefreshTask();
        assertThat(refreshTask.getInterval().millis()).isEqualTo(1000);
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();
        IndexShard shard = indexService.getShard(0);
        execute("insert into test (x, data) values (1, 'foo')");
        // now disable the refresh
        execute("alter table test set (refresh_interval = -1)");
        // when we update we reschedule the existing task AND fire off an async refresh to make sure we make everything visible
        // before that this is why we need to wait for the refresh task to be unscheduled and the first doc to be visible
        assertThat(refreshTask.isClosed()).isTrue();
        refreshTask = indexService.getRefreshTask();
        assertBusy(() -> {
            // this one either becomes visible due to a concurrently running scheduled refresh OR due to the force refresh
            // we are running on updateMetadata if the interval changes
            try (Engine.Searcher searcher = shard.acquireSearcher(indexName)) {
                TopDocs search = searcher.search(new MatchAllDocsQuery(), 10);
                assertThat(search.totalHits.value()).isEqualTo(1);
            }
        });
        assertThat(refreshTask.isClosed()).isFalse();
        // refresh every millisecond
        execute("insert into test (x, data) values (2, 'foo')");
        execute("alter table test set (refresh_interval = '1ms')");
        assertThat(refreshTask.isClosed()).isTrue();

        assertBusy(() -> {
            // this one becomes visible due to the force refresh we are running on updateMetadata if the interval changes
            try (Engine.Searcher searcher = shard.acquireSearcher(indexName)) {
                TopDocs search = searcher.search(new MatchAllDocsQuery(), 10);
                assertThat(search.totalHits.value()).isEqualTo(2);
            }
        });
        execute("insert into test (x, data) values (3, 'foo')");

        assertBusy(() -> {
            // this one becomes visible due to the scheduled refresh
            try (Engine.Searcher searcher = shard.acquireSearcher("test")) {
                TopDocs search = searcher.search(new MatchAllDocsQuery(), 10);
                assertThat(search.totalHits.value()).isEqualTo(3);
            }
        });
    }

    @Test
    public void testAsyncFsyncActuallyWorks() throws Exception {
        execute("create table test(x int, data string) clustered into 1 shards with (\"translog.sync_interval\" = '100ms', " +
                "\"translog.durability\" = 'ASYNC')");
        IndexService indexService = getIndexService("test");
        ensureGreen();
        assertThat(indexService.getRefreshTask().mustReschedule()).isTrue();
        execute("insert into test (x, data) values (1, 'foo')");
        IndexShard shard = indexService.getShard(0);
        assertBusy(() -> assertThat(shard.isSyncNeeded()).isFalse());
    }

    @Test
    public void testRescheduleAsyncFsync() throws Exception {
        execute("create table test(x int, data string) clustered into 1 shards with (\"translog.sync_interval\" = '100ms', \"translog.durability\" = 'REQUEST')");
        IndexService indexService = getIndexService("test");

        ensureGreen();
        assertThat(indexService.getFsyncTask()).isNull();

        execute("alter table test set (\"translog.durability\" = 'ASYNC')");

        assertThat(indexService.getFsyncTask()).isNotNull();
        assertThat(indexService.getFsyncTask().mustReschedule()).isTrue();
        execute("insert into test (x, data) values (1, 'foo')");
        assertThat(indexService.getFsyncTask()).isNotNull();
        final IndexShard shard = indexService.getShard(0);
        assertBusy(() -> assertThat(shard.isSyncNeeded()).isFalse());

        execute("alter table test set (\"translog.durability\" = 'REQUEST')");
        assertThat(indexService.getFsyncTask()).isNull();

        execute("alter table test set (\"translog.durability\" = 'ASYNC')");
        assertThat(indexService.getFsyncTask()).isNotNull();
    }

    @Test
    public void testAsyncTranslogTrimActuallyWorks() throws Exception {
        execute("create table test(x int, data string) clustered into 1 shards with (\"translog.sync_interval\" = '100ms')");
        IndexService indexService = getIndexService("test");

        ensureGreen();
        assertThat(indexService.getTrimTranslogTask().mustReschedule()).isTrue();
        execute("insert into test (x, data) values (1, 'foo')");
        IndexShard shard = indexService.getShard(0);
        flushShard(shard, true);
        assertBusy(() -> assertThat(EngineTestCase.getTranslog(getEngine(shard)).totalOperations()).isZero());
    }

    @Test
    public void testAsyncTranslogTrimTaskOnClosedIndex() throws Exception {
        execute ("create table test(x int) clustered into 1 shards");
        var indexService = getIndexService("test");
        var indexName = indexService.index().getName();
        var partitionName = IndexName.decode(indexName).toPartitionName();

        // Setting not exposed via SQL
        client()
            .updateSettings(new UpdateSettingsRequest(
                Settings.builder()
                    .put(TRANSLOG_RETENTION_CHECK_INTERVAL_SETTING.getKey(), "100ms")
                    .build(),
                List.of(partitionName)
            )).get();

        Translog translog = IndexShardTestCase.getTranslog(indexService.getShard(0));

        int translogOps = 0;
        final int numDocs = scaledRandomIntBetween(10, 100);
        for (int i = 0; i < numDocs; i++) {
            execute("insert into test (x) values (?)", new Object[]{i});
            translogOps++;
            if(randomBoolean()) {
                for (IndexShard indexShard : indexService) {
                    flushShard(indexShard, true);
                }
                translogOps = 0;
            }
        }
        assertThat(translog.totalOperations()).isEqualTo(translogOps);
        assertThat(translog.stats().estimatedNumberOfOperations()).isEqualTo(translogOps);

        execute("alter table test close");

        indexService =  getIndicesService().indexServiceSafe(indexService.index());
        assertThat(indexService.getTrimTranslogTask().mustReschedule()).isTrue();

        final Engine readOnlyEngine = getEngine(indexService.getShard(0));
        assertBusy(() ->
            assertThat(readOnlyEngine.getTranslogStats().getTranslogSizeInBytes())
                .isEqualTo(Translog.DEFAULT_HEADER_SIZE_IN_BYTES));

        execute("alter table test open");
        ensureGreen();

        indexService = getIndexService("test");
        translog = IndexShardTestCase.getTranslog(indexService.getShard(0));
        assertThat(translog.totalOperations()).isEqualTo(0);
        assertThat(translog.stats().estimatedNumberOfOperations()).isEqualTo(0);
    }

    public void testIllegalFsyncInterval() {
        Asserts.assertSQLError(() -> execute("create table test(x int, data string) clustered into 1 shards with (\"translog.sync_interval\" = '0ms')"))
            .hasPGError(INTERNAL_ERROR)
            .hasHTTPError(BAD_REQUEST, 4000)
            .hasMessageContaining("failed to parse value [0ms] for setting [index.translog.sync_interval], must be >= [100ms]");
    }

    @Test
    public void testUpdateSyncIntervalDynamically() throws Exception {
        execute("create table test (x int) clustered into 1 shards with (\"translog.sync_interval\" = '10s')");
        IndexService indexService = getIndexService("test");
        var indexUUID = indexService.index().getUUID();

        ensureGreen();
        assertThat(indexService.getFsyncTask()).isNull();

        execute("alter table test set (\"translog.sync_interval\" = '5s', \"translog.durability\" = 'async')");

        assertThat(indexService.getFsyncTask()).isNotNull();
        assertThat(indexService.getFsyncTask().mustReschedule()).isTrue();

        IndexMetadata indexMetadata = client()
            .state(new ClusterStateRequest())
            .get().getState().metadata().index(indexUUID);
        assertThat(indexMetadata.getSettings().get(IndexSettings.INDEX_TRANSLOG_SYNC_INTERVAL_SETTING.getKey())).isEqualTo("5s");

        execute("alter table test close");
        execute("alter table test set (\"translog.sync_interval\" = '20s')");
        indexMetadata = client()
            .state(new ClusterStateRequest())
            .get().getState().metadata().index(indexUUID);
        assertThat(indexMetadata.getSettings().get(IndexSettings.INDEX_TRANSLOG_SYNC_INTERVAL_SETTING.getKey())).isEqualTo("20s");
    }

    @Test
    public void testTranslogIndexerInstanceIsShared() {
        execute("create table test (x int, y object(ignored))");
        IndexService indexService = getIndexService("test");
        TranslogIndexer ti1 = indexService.getTranslogIndexer();
        TranslogIndexer ti2 = indexService.getTranslogIndexer();
        assertThat(ti1).isSameAs(ti2);
        execute("alter table test add column z int");
        TranslogIndexer ti3 = indexService.getTranslogIndexer();
        assertThat(ti1).isNotSameAs(ti3);
    }

    private IndexService getIndexService(String index) {
        return getIndicesService().indexServiceSafe(resolveIndex(index));
    }

    private IndicesService getIndicesService() {
        return cluster().getInstances(IndicesService.class).iterator().next();
    }
}

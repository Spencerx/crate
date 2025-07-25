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

import static io.crate.testing.Asserts.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.IntegTestCase;
import org.junit.Test;

import com.carrotsearch.randomizedtesting.ThreadFilter;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import io.crate.blob.PutChunkRequest;
import io.crate.blob.StartBlobRequest;
import io.crate.blob.TransportPutChunk;
import io.crate.blob.TransportStartBlob;
import io.crate.blob.v2.BlobIndex;
import io.crate.blob.v2.BlobIndicesService;
import io.crate.blob.v2.BlobShard;
import io.crate.common.Hex;
import io.crate.test.utils.BlobsUtil;

@IntegTestCase.ClusterScope(scope = IntegTestCase.Scope.SUITE, numDataNodes = 0, numClientNodes = 0)
@ThreadLeakFilters(filters = {RecoveryTests.RecoveryTestThreadFilter.class})
@WindowsIncompatible
public class RecoveryTests extends BlobIntegrationTestBase {

    public static class RecoveryTestThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            return (t.getName().contains("blob-uploader"));
        }
    }

    // the time to sleep between chunk requests in upload
    private AtomicInteger timeBetweenChunks = new AtomicInteger();

    static {
        System.setProperty("tests.short_timeouts", "true");
    }


    private ShardId resolveShardId(String index, String digest) {
        return clusterService().operationRouting()
            .indexShards(clusterService().state(), resolveIndex(index).getUUID(), digest, null)
            .shardId();
    }

    private String uploadFile(Client client, String content) {
        byte[] digest = BlobsUtil.digest(content);
        String digestString = Hex.encodeHexString(digest);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        logger.trace("Uploading {} digest {}", content, digestString);
        BytesArray bytes = new BytesArray(new byte[]{contentBytes[0]});
        if (content.length() == 1) {
            FutureUtils.get(client.execute(TransportStartBlob.ACTION,
                new StartBlobRequest(
                    resolveShardId(BlobIndex.fullIndexName("test"), digestString),
                    digest,
                    bytes,
                    true
                )));
        } else {
            StartBlobRequest startBlobRequest = new StartBlobRequest(
                resolveShardId(BlobIndex.fullIndexName("test"), digestString),
                digest,
                bytes,
                false
            );
            FutureUtils.get(client.execute(TransportStartBlob.ACTION, startBlobRequest));
            for (int i = 1; i < contentBytes.length; i++) {
                try {
                    Thread.sleep(timeBetweenChunks.get());
                } catch (InterruptedException ex) {
                    Thread.interrupted();
                }
                bytes = new BytesArray(new byte[]{contentBytes[i]});
                try {
                    FutureUtils.get(client.execute(TransportPutChunk.ACTION,
                        new PutChunkRequest(
                            resolveShardId(BlobIndex.fullIndexName("test"), digestString),
                            digest,
                            startBlobRequest.transferId(),
                            bytes,
                            i,
                            (i + 1) == content.length()
                        )
                    ));
                } catch (IllegalStateException ex) {
                    Thread.interrupted();
                }
            }
        }
        logger.trace("Upload finished {} digest {}", content, digestString);

        return digestString;
    }


    private String genFile(long numChars) {
        StringBuilder sb = new StringBuilder();
        int charValue = 64;
        for (long i = 0; i <= numChars + 10; i++) {
            charValue++;
            if (charValue > 90) {
                charValue = 64;
            }
            sb.append(Character.toChars(charValue));
        }

        return sb.toString();
    }

    @Test
    public void testPrimaryRelocationWhileIndexing() throws Exception {
        final int numberOfRelocations = 1;
        final int numberOfWriters = 2;

        final String node1 = cluster().startNode();

        logger.trace("--> creating test blob table ...");
        execute("create blob table test clustered into 1 shards with (number_of_replicas = 0)");

        logger.trace("--> starting [node2] ...");
        final String node2 = cluster().startNode();
        ensureGreen();

        final AtomicLong idGenerator = new AtomicLong();
        final AtomicLong indexCounter = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean(false);
        Thread[] writers = new Thread[numberOfWriters];
        final CountDownLatch stopLatch = new CountDownLatch(writers.length);

        logger.trace("--> starting {} blob upload threads", writers.length);
        final List<String> uploadedDigests = Collections.synchronizedList(new ArrayList<String>(writers.length));
        for (int i = 0; i < writers.length; i++) {
            final int indexerId = i;
            writers[i] = new Thread() {
                @Override
                public void run() {
                    try {
                        logger.trace("**** starting blob upload thread {}", indexerId);
                        while (!stop.get()) {
                            long id = idGenerator.incrementAndGet();
                            String digest = uploadFile(cluster().client(node1), genFile(id));
                            uploadedDigests.add(digest);
                            indexCounter.incrementAndGet();
                        }
                        logger.trace("**** done indexing thread {}", indexerId);
                    } catch (Exception e) {
                        logger.warn("**** failed indexing thread {}", e, indexerId);
                    } finally {
                        stopLatch.countDown();
                    }
                }
            };
            writers[i].setName("blob-uploader-thread");
            // dispatch threads from parent, ignoring possible leaking threads
            writers[i].setDaemon(true);
            writers[i].start();
        }

        logger.trace("--> waiting for 2 blobs to be uploaded ...");
        while (uploadedDigests.size() < 2) {
            Thread.sleep(10);
        }
        logger.trace("--> 2 blobs uploaded");

        // increase time between chunks in order to make sure that the upload is taking place while relocating
        timeBetweenChunks.set(10);

        logger.trace("--> starting relocations...");
        for (int i = 0; i < numberOfRelocations; i++) {
            String fromNode = (i % 2 == 0) ? node1 : node2;
            String toNode = node1.equals(fromNode) ? node2 : node1;
            logger.trace("--> START relocate the shard from {} to {}", fromNode, toNode);

            execute("alter table blob.test reroute move shard 0 from ? to ?", new Object[] { fromNode, toNode });
            assertBusy(() -> {
                execute("select node['name'], state from sys.shards where table_name = 'test' and id = 0");
                assertThat(response).hasRows(toNode + "| STARTED");
            });
            logger.trace("--> DONE relocate the shard from {} to {}", fromNode, toNode);
        }
        logger.trace("--> done relocations");

        logger.trace("--> marking and waiting for upload threads to stop ...");
        timeBetweenChunks.set(0);
        stop.set(true);
        assertThat(stopLatch.await(60, TimeUnit.SECONDS)).isTrue();
        logger.trace("--> uploading threads stopped");

        logger.trace("--> expected {} got {}", indexCounter.get(), uploadedDigests.size());
        assertThat(uploadedDigests.size()).isEqualTo(indexCounter.get());

        BlobIndicesService blobIndicesService = cluster().getInstance(BlobIndicesService.class, node2);
        for (String digest : uploadedDigests) {
            BlobShard blobShard = blobIndicesService.localBlobShard(resolveIndex(BlobIndex.fullIndexName("test")).getUUID(), digest);
            assertThat(blobShard.blobContainer().getFile(digest).length()).isGreaterThanOrEqualTo(1);
        }

        for (Thread writer : writers) {
            writer.join(6000);
        }
    }
}

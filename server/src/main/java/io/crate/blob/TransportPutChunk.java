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

package io.crate.blob;

import java.io.IOException;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportPutChunk extends TransportReplicationAction<PutChunkRequest, PutChunkReplicaRequest, PutChunkResponse> {

    public static final Action ACTION = new Action();
    private final BlobTransferTarget transferTarget;

    public static class Action extends ActionType<PutChunkResponse> {
        private static final String NAME = "internal:crate:blob/put_chunk";

        private Action() {
            super(NAME);
        }
    }

    @Inject
    public TransportPutChunk(Settings settings,
                             TransportService transportService,
                             ClusterService clusterService,
                             IndicesService indicesService,
                             ThreadPool threadPool,
                             ShardStateAction shardStateAction,
                             BlobTransferTarget transferTarget) {
        super(
            settings,
            ACTION.name(),
            transportService,
            clusterService,
            indicesService,
            threadPool,
            shardStateAction,
            PutChunkRequest::new,
            PutChunkReplicaRequest::new,
            ThreadPool.Names.WRITE
        );
        this.transferTarget = transferTarget;
    }

    @Override
    protected PutChunkResponse newResponseInstance(StreamInput in) throws IOException {
        return new PutChunkResponse(in);
    }

    @Override
    protected void shardOperationOnPrimary(PutChunkRequest request,
                                           IndexShard primary,
                                           ActionListener<PrimaryResult<PutChunkReplicaRequest, PutChunkResponse>> listener) {
        try {
            PutChunkResponse response = new PutChunkResponse();
            transferTarget.continueTransfer(request, response);
            final PutChunkReplicaRequest replicaRequest = new PutChunkReplicaRequest(
                request.shardId(),
                clusterService.localNode().getId(),
                request.transferId(),
                request.currentPos(),
                request.content(),
                request.isLast()
            );
            listener.onResponse(new PrimaryResult<>(replicaRequest, response));
        } catch (Exception ex) {
            listener.onFailure(ex);
        }
    }

    @Override
    protected ReplicaResult shardOperationOnReplica(PutChunkReplicaRequest shardRequest, IndexShard replica) {
        PutChunkResponse response = new PutChunkResponse();
        transferTarget.continueTransfer(shardRequest, response);
        return new ReplicaResult();
    }
}

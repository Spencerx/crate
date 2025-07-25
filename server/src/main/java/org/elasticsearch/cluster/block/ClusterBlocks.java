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

package org.elasticsearch.cluster.block;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.elasticsearch.cluster.AbstractDiffable;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.rest.RestStatus;
import org.jetbrains.annotations.Nullable;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;

/**
 * Represents current cluster level blocks to block dirty operations done against the cluster.
 */
public class ClusterBlocks extends AbstractDiffable<ClusterBlocks> {

    public static final ClusterBlocks EMPTY_CLUSTER_BLOCK = new ClusterBlocks(emptySet(), ImmutableOpenMap.of());

    private final Set<ClusterBlock> global;

    private final ImmutableOpenMap<String, Set<ClusterBlock>> indicesBlocks;

    private final EnumMap<ClusterBlockLevel, ImmutableLevelHolder> levelHolders;

    ClusterBlocks(Set<ClusterBlock> global, ImmutableOpenMap<String, Set<ClusterBlock>> indicesBlocks) {
        this.global = global;
        this.indicesBlocks = indicesBlocks;
        levelHolders = generateLevelHolders(global, indicesBlocks);
    }

    public Set<ClusterBlock> global() {
        return global;
    }

    public ImmutableOpenMap<String, Set<ClusterBlock>> indices() {
        return indicesBlocks;
    }

    public Set<ClusterBlock> global(ClusterBlockLevel level) {
        return levelHolders.get(level).global();
    }

    public Set<ClusterBlock> global(RestStatus status) {
        Set<ClusterBlock> blocks = new HashSet<>();
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.status().equals(status)) {
                blocks.add(clusterBlock);
            }
        }
        return blocks;
    }

    public ImmutableOpenMap<String, Set<ClusterBlock>> indices(ClusterBlockLevel level) {
        return levelHolders.get(level).indices();
    }

    private Set<ClusterBlock> blocksForIndex(ClusterBlockLevel level, String index) {
        return indices(level).getOrDefault(index, emptySet());
    }

    private static EnumMap<ClusterBlockLevel, ImmutableLevelHolder> generateLevelHolders(Set<ClusterBlock> global,
            ImmutableOpenMap<String, Set<ClusterBlock>> indicesBlocks) {
        EnumMap<ClusterBlockLevel, ImmutableLevelHolder> levelHolders = new EnumMap<>(ClusterBlockLevel.class);
        for (final ClusterBlockLevel level : ClusterBlockLevel.values()) {
            Predicate<ClusterBlock> containsLevel = block -> block.contains(level);
            Set<ClusterBlock> newGlobal = unmodifiableSet(global.stream()
                .filter(containsLevel)
                .collect(toSet()));

            ImmutableOpenMap.Builder<String, Set<ClusterBlock>> indicesBuilder = ImmutableOpenMap.builder();
            for (ObjectObjectCursor<String, Set<ClusterBlock>> entry : indicesBlocks) {
                indicesBuilder.put(entry.key, unmodifiableSet(entry.value.stream()
                    .filter(containsLevel)
                    .collect(toSet())));
            }
            levelHolders.put(level, new ImmutableLevelHolder(newGlobal, indicesBuilder.build()));
        }
        return levelHolders;
    }

    /**
     * Returns {@code true} if one of the global blocks as its disable state persistence flag set.
     */
    public boolean disableStatePersistence() {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.disableStatePersistence()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGlobalBlock(ClusterBlock block) {
        return global.contains(block);
    }

    public boolean hasGlobalBlockWithId(final int blockId) {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.id() == blockId) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGlobalBlockWithLevel(ClusterBlockLevel level) {
        return global(level).size() > 0;
    }

    /**
     * Is there a global block with the provided status?
     */
    public boolean hasGlobalBlockWithStatus(final RestStatus status) {
        for (ClusterBlock clusterBlock : global) {
            if (clusterBlock.status().equals(status)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIndexBlock(String indexUUID, ClusterBlock block) {
        return indicesBlocks.containsKey(indexUUID) && indicesBlocks.get(indexUUID).contains(block);
    }

    public boolean hasIndexBlockWithId(String indexUUID, int blockId) {
        final Set<ClusterBlock> clusterBlocks = indicesBlocks.get(indexUUID);
        if (clusterBlocks != null) {
            for (ClusterBlock clusterBlock : clusterBlocks) {
                if (clusterBlock.id() == blockId) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public ClusterBlock getIndexBlockWithId(final String indexUUID, final int blockId) {
        final Set<ClusterBlock> clusterBlocks = indicesBlocks.get(indexUUID);
        if (clusterBlocks != null) {
            for (ClusterBlock clusterBlock : clusterBlocks) {
                if (clusterBlock.id() == blockId) {
                    return clusterBlock;
                }
            }
        }
        return null;
    }

    private boolean globalBlocked(ClusterBlockLevel level) {
        return global(level).isEmpty() == false;
    }

    public ClusterBlockException globalBlockedException(ClusterBlockLevel level) {
        if (globalBlocked(level) == false) {
            return null;
        }
        return new ClusterBlockException(global(level));
    }

    public ClusterBlockException indexBlockedException(ClusterBlockLevel level, String index) {
        if (!indexBlocked(level, index)) {
            return null;
        }
        Stream<ClusterBlock> blocks = concat(
                global(level).stream(),
                blocksForIndex(level, index).stream());
        return new ClusterBlockException(unmodifiableSet(blocks.collect(toSet())));
    }

    public boolean indexBlocked(ClusterBlockLevel level, String index) {
        return globalBlocked(level) || blocksForIndex(level, index).isEmpty() == false;
    }

    public ClusterBlockException indicesBlockedException(ClusterBlockLevel level, String[] indices) {
        boolean indexIsBlocked = false;
        for (String index : indices) {
            if (indexBlocked(level, index)) {
                indexIsBlocked = true;
            }
        }
        if (globalBlocked(level) == false && indexIsBlocked == false) {
            return null;
        }
        Function<String, Stream<ClusterBlock>> blocksForIndexAtLevel = index -> blocksForIndex(level, index).stream();
        Stream<ClusterBlock> blocks = concat(
                global(level).stream(),
                Stream.of(indices).flatMap(blocksForIndexAtLevel));
        return new ClusterBlockException(unmodifiableSet(blocks.collect(toSet())));
    }

    /**
     * Returns <code>true</code> iff non of the given have a {@link ClusterBlockLevel#METADATA_WRITE} in place where the
     * {@link ClusterBlock#isAllowReleaseResources()} returns <code>false</code>. This is used in places where resources will be released
     * like the deletion of an index to free up resources on nodes.
     * @param indices the indices to check
     */
    public ClusterBlockException indicesAllowReleaseResources(String[] indices) {
        final Function<String, Stream<ClusterBlock>> blocksForIndexAtLevel = index ->
            blocksForIndex(ClusterBlockLevel.METADATA_WRITE, index).stream();
        Stream<ClusterBlock> blocks = concat(
            global(ClusterBlockLevel.METADATA_WRITE).stream(),
            Stream.of(indices).flatMap(blocksForIndexAtLevel)).filter(clusterBlock -> clusterBlock.isAllowReleaseResources() == false);
        Set<ClusterBlock> clusterBlocks = unmodifiableSet(blocks.collect(toSet()));
        if (clusterBlocks.isEmpty()) {
            return null;
        }
        return new ClusterBlockException(clusterBlocks);
    }


    @Override
    public String toString() {
        if (global.isEmpty() && indices().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("blocks: \n");
        if (global.isEmpty() == false) {
            sb.append("   _global_:\n");
            for (ClusterBlock block : global) {
                sb.append("      ").append(block);
            }
        }
        for (ObjectObjectCursor<String, Set<ClusterBlock>> entry : indices()) {
            sb.append("   ").append(entry.key).append(":\n");
            for (ClusterBlock block : entry.value) {
                sb.append("      ").append(block);
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        writeBlockSet(global, out);
        out.writeMap(indicesBlocks, StreamOutput::writeString, (o, s) -> writeBlockSet(s, o));
    }

    private static void writeBlockSet(Set<ClusterBlock> blocks, StreamOutput out) throws IOException {
        out.writeCollection(blocks);
    }

    public static ClusterBlocks readFrom(StreamInput in) throws IOException {
        final Set<ClusterBlock> global = readBlockSet(in);
        ImmutableOpenMap<String, Set<ClusterBlock>> indicesBlocks =
                in.readImmutableMap(i -> i.readString().intern(), ClusterBlocks::readBlockSet);
        if (global.isEmpty() && indicesBlocks.isEmpty()) {
            return EMPTY_CLUSTER_BLOCK;
        }
        return new ClusterBlocks(global, indicesBlocks);
    }

    private static Set<ClusterBlock> readBlockSet(StreamInput in) throws IOException {
        final Set<ClusterBlock> blocks = in.readSet(ClusterBlock::new);
        return blocks.isEmpty() ? blocks : unmodifiableSet(blocks);
    }

    public static Diff<ClusterBlocks> readDiffFrom(StreamInput in) throws IOException {
        return AbstractDiffable.readDiffFrom(ClusterBlocks::readFrom, in);
    }

    record ImmutableLevelHolder(Set<ClusterBlock> global,
                                ImmutableOpenMap<String, Set<ClusterBlock>> indices) {
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + global.hashCode();
        result = 31 * result + indicesBlocks.hashCode();
        result = 31 * result + levelHolders.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ClusterBlocks other
            && global.equals(other.global)
            && indicesBlocks.equals(other.indicesBlocks)
            && levelHolders.equals(other.levelHolders);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final Set<ClusterBlock> global = new HashSet<>();

        private final Map<String, Set<ClusterBlock>> indices = new HashMap<>();

        public Builder() {
        }

        public Builder blocks(ClusterBlocks blocks) {
            global.addAll(blocks.global());
            for (ObjectObjectCursor<String, Set<ClusterBlock>> entry : blocks.indices()) {
                if (!indices.containsKey(entry.key)) {
                    indices.put(entry.key, new HashSet<>());
                }
                indices.get(entry.key).addAll(entry.value);
            }
            return this;
        }

        public Builder addBlocks(IndexMetadata indexMetadata) {
            String indexUUID = indexMetadata.getIndex().getUUID();
            if (indexMetadata.getState() == IndexMetadata.State.CLOSE) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_CLOSED_BLOCK);
            }
            if (IndexMetadata.INDEX_READ_ONLY_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_READ_ONLY_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_READ_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_READ_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_WRITE_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_WRITE_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_METADATA_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_METADATA_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexUUID, IndexMetadata.INDEX_READ_ONLY_ALLOW_DELETE_BLOCK);
            }
            return this;
        }

        public Builder addBlocksWithIndexName(IndexMetadata indexMetadata) {
            String indexName = indexMetadata.getIndex().getName();
            if (indexMetadata.getState() == IndexMetadata.State.CLOSE) {
                addIndexBlock(indexName, IndexMetadata.INDEX_CLOSED_BLOCK);
            }
            if (IndexMetadata.INDEX_READ_ONLY_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexName, IndexMetadata.INDEX_READ_ONLY_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_READ_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexName, IndexMetadata.INDEX_READ_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_WRITE_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexName, IndexMetadata.INDEX_WRITE_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_METADATA_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexName, IndexMetadata.INDEX_METADATA_BLOCK);
            }
            if (IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.get(indexMetadata.getSettings())) {
                addIndexBlock(indexName, IndexMetadata.INDEX_READ_ONLY_ALLOW_DELETE_BLOCK);
            }
            return this;
        }

        public Builder updateBlocks(IndexMetadata indexMetadata) {
            // let's remove all blocks for this index and add them back -- no need to remove all individual blocks....
            indices.remove(indexMetadata.getIndex().getUUID());
            return addBlocks(indexMetadata);
        }

        public Builder addGlobalBlock(ClusterBlock block) {
            global.add(block);
            return this;
        }

        public Builder removeGlobalBlock(ClusterBlock block) {
            global.remove(block);
            return this;
        }

        public Builder removeGlobalBlock(int blockId) {
            global.removeIf(block -> block.id() == blockId);
            return this;
        }


        public Builder addIndexBlock(String indexUUID, ClusterBlock block) {
            if (!indices.containsKey(indexUUID)) {
                indices.put(indexUUID, new HashSet<>());
            }
            indices.get(indexUUID).add(block);
            return this;
        }

        public Builder removeIndexBlocks(String indexUUID) {
            if (!indices.containsKey(indexUUID)) {
                return this;
            }
            indices.remove(indexUUID);
            return this;
        }

        public Builder removeIndexBlock(String index, ClusterBlock block) {
            if (!indices.containsKey(index)) {
                return this;
            }
            indices.get(index).remove(block);
            if (indices.get(index).isEmpty()) {
                indices.remove(index);
            }
            return this;
        }

        public Builder removeIndexBlockWithId(String index, int blockId) {
            final Set<ClusterBlock> indexBlocks = indices.get(index);
            if (indexBlocks == null) {
                return this;
            }
            indexBlocks.removeIf(block -> block.id() == blockId);
            if (indexBlocks.isEmpty()) {
                indices.remove(index);
            }
            return this;
        }

        public ClusterBlocks build() {
            if (indices.isEmpty() && global.isEmpty()) {
                return EMPTY_CLUSTER_BLOCK;
            }
            // We copy the block sets here in case of the builder is modified after build is called
            ImmutableOpenMap.Builder<String, Set<ClusterBlock>> indicesBuilder = ImmutableOpenMap.builder(indices.size());
            for (Map.Entry<String, Set<ClusterBlock>> entry : indices.entrySet()) {
                indicesBuilder.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new ClusterBlocks(Set.copyOf(global), indicesBuilder.build());
        }
    }
}

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

package org.elasticsearch.common.settings;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

import org.elasticsearch.cluster.metadata.AutoExpandReplicas;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.ExistingShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ShardsLimitAllocationDecider;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.index.MergeSchedulerConfig;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.store.FsDirectoryFactory;
import org.elasticsearch.index.store.Store;

import io.crate.blob.v2.BlobIndicesService;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.replication.logical.LogicalReplicationSettings;

/**
 * Encapsulates all valid index level settings.
 * @see Property#IndexScope
 */
public final class IndexScopedSettings extends AbstractScopedSettings {

    public static final Predicate<String> INDEX_SETTINGS_KEY_PREDICATE = (s) -> s.startsWith(IndexMetadata.INDEX_SETTING_PREFIX);

    // this allows analysis settings to be passed
    public static final Set<Setting<?>> BUILT_IN_INDEX_SETTINGS = Set.of(
        MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY,
        MergeSchedulerConfig.AUTO_THROTTLE_SETTING,
        MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING,
        MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING,
        IndexMetadata.SETTING_INDEX_VERSION_CREATED,
        IndexMetadata.INDEX_ROUTING_EXCLUDE_GROUP_SETTING,
        IndexMetadata.INDEX_ROUTING_INCLUDE_GROUP_SETTING,
        IndexMetadata.INDEX_ROUTING_REQUIRE_GROUP_SETTING,
        AutoExpandReplicas.SETTING,
        IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING,
        IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING,
        IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING,
        IndexMetadata.INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING,
        IndexMetadata.INDEX_READ_ONLY_SETTING,
        IndexMetadata.INDEX_BLOCKS_READ_SETTING,
        IndexMetadata.INDEX_BLOCKS_WRITE_SETTING,
        IndexMetadata.INDEX_BLOCKS_METADATA_SETTING,
        IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING,
        IndexMetadata.INDEX_DATA_PATH_SETTING,
        IndexMetadata.INDEX_FORMAT_SETTING,
        IndexMetadata.VERIFIED_BEFORE_CLOSE_SETTING,
        MergePolicyConfig.INDEX_COMPOUND_FORMAT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_DELETES_PCT_ALLOWED_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_EXPUNGE_DELETES_ALLOWED_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_EXPLICIT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGED_SEGMENT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_RECLAIM_DELETES_WEIGHT_SETTING,
        IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING,
        IndexSettings.INDEX_REFRESH_INTERVAL_SETTING,
        IndexSettings.MAX_NGRAM_DIFF_SETTING,
        IndexSettings.MAX_SHINGLE_DIFF_SETTING,
        IndexSettings.INDEX_TRANSLOG_SYNC_INTERVAL_SETTING,
        IndexSettings.MAX_REFRESH_LISTENERS_PER_SHARD,
        ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING,
        IndexSettings.INDEX_GC_DELETES_SETTING,
        IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING,
        IndexSettings.INDEX_SOFT_DELETES_RETENTION_LEASE_PERIOD_SETTING,
        UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING,
        EnableAllocationDecider.INDEX_ROUTING_REBALANCE_ENABLE_SETTING,
        EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE_SETTING,
        IndexSettings.INDEX_FLUSH_AFTER_MERGE_THRESHOLD_SIZE_SETTING,
        IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING,
        IndexSettings.INDEX_TRANSLOG_GENERATION_THRESHOLD_SIZE_SETTING,
        IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING,
        IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING,
        IndexSettings.INDEX_SEARCH_IDLE_AFTER,
        Store.INDEX_STORE_STATS_REFRESH_INTERVAL_SETTING,
        DocTableInfo.TOTAL_COLUMNS_LIMIT,
        DocTableInfo.DEPTH_LIMIT_SETTING,
        IndexModule.INDEX_STORE_TYPE_SETTING,
        IndexModule.INDEX_QUERY_CACHE_ENABLED_SETTING,
        FsDirectoryFactory.INDEX_LOCK_FACTOR_SETTING,
        EngineConfig.INDEX_CODEC_SETTING,
        IndexMetadata.SETTING_WAIT_FOR_ACTIVE_SHARDS,
        IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING,
        Setting.groupSetting("index.analysis.", Property.IndexScope),
        BlobIndicesService.SETTING_INDEX_BLOBS_ENABLED,
        BlobIndicesService.SETTING_INDEX_BLOBS_PATH,
        ExistingShardsAllocator.EXISTING_SHARDS_ALLOCATOR_SETTING,
        LogicalReplicationSettings.REPLICATION_SUBSCRIPTION_NAME,
        LogicalReplicationSettings.PUBLISHER_INDEX_UUID
    );

    public static final IndexScopedSettings DEFAULT_SCOPED_SETTINGS = new IndexScopedSettings(Settings.EMPTY, BUILT_IN_INDEX_SETTINGS);

    public IndexScopedSettings(Settings settings, Set<Setting<?>> settingsSet) {
        super(settings, settingsSet, Collections.emptySet(), Property.IndexScope);
    }

    private IndexScopedSettings(Settings settings, IndexScopedSettings other, IndexMetadata metadata) {
        super(settings, metadata.getSettings(), other);
    }

    public IndexScopedSettings copy(Settings settings, IndexMetadata metadata) {
        return new IndexScopedSettings(settings, this, metadata);
    }

    @Override
    protected void validateSettingKey(Setting<?> setting) {
        if (setting.getKey().startsWith("index.") == false) {
            throw new IllegalArgumentException("illegal settings key: [" + setting.getKey() + "] must start with [index.]");
        }
        super.validateSettingKey(setting);
    }

    @Override
    public boolean isPrivateSetting(String key) {
        switch (key) {
            case IndexMetadata.SETTING_CREATION_DATE:
            case IndexMetadata.SETTING_INDEX_UUID:
            case IndexMetadata.SETTING_HISTORY_UUID:
            case IndexMetadata.SETTING_VERSION_UPGRADED:
            case MergePolicyConfig.INDEX_MERGE_ENABLED:
            case IndexMetadata.INDEX_RESIZE_SOURCE_UUID_KEY:
            case IndexMetadata.INDEX_RESIZE_SOURCE_NAME_KEY:
            case IndexMetadata.SETTING_INDEX_NAME:
                return true;
            default:
                return IndexMetadata.INDEX_ROUTING_INITIAL_RECOVERY_GROUP_SETTING.getRawKey().match(key);
        }
    }
}

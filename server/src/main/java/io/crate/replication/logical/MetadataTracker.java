/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.replication.logical;

import static io.crate.replication.logical.LogicalReplicationSettings.NON_REPLICATED_SETTINGS;
import static io.crate.replication.logical.LogicalReplicationSettings.PUBLISHER_INDEX_UUID;
import static io.crate.replication.logical.LogicalReplicationSettings.REPLICATION_INDEX_ROUTING_ACTIVE;

import java.io.Closeable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.cluster.snapshots.restore.TableOrPartition;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.support.master.AcknowledgedRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataDeleteIndexService;
import org.elasticsearch.cluster.metadata.MetadataUpgradeService;
import org.elasticsearch.cluster.metadata.RelationMetadata;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.Scheduler.Cancellable;
import org.elasticsearch.threadpool.ThreadPool;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import io.crate.concurrent.CountdownFuture;
import io.crate.exceptions.SQLExceptions;
import io.crate.execution.support.RetryRunnable;
import io.crate.metadata.PartitionName;
import io.crate.metadata.RelationName;
import io.crate.replication.logical.action.DropSubscriptionAction;
import io.crate.replication.logical.action.PublicationsStateAction;
import io.crate.replication.logical.action.PublicationsStateAction.Response;
import io.crate.replication.logical.action.UpdateSubscriptionAction;
import io.crate.replication.logical.exceptions.SubscriptionUnknownException;
import io.crate.replication.logical.metadata.Subscription;
import io.crate.replication.logical.metadata.Subscription.RelationState;
import io.crate.replication.logical.metadata.SubscriptionsMetadata;

public final class MetadataTracker implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger(MetadataTracker.class);

    private final Settings settings;
    private final ThreadPool threadPool;
    private final LogicalReplicationService replicationService;
    private final LogicalReplicationSettings replicationSettings;
    private final Function<String, Client> remoteClient;
    private final ClusterService clusterService;
    private final IndexScopedSettings indexScopedSettings;
    private final AllocationService allocationService;
    private final MetadataUpgradeService metadataUpgradeService;

    // Using a copy-on-write approach. The assumption is that subscription changes are rare and reads happen more frequently
    private volatile Set<String> subscriptionsToTrack = Set.of();
    private volatile Scheduler.Cancellable cancellable;
    private volatile boolean isActive = false;

    public MetadataTracker(Settings settings,
                           IndexScopedSettings indexScopedSettings,
                           ThreadPool threadPool,
                           LogicalReplicationService replicationService,
                           LogicalReplicationSettings replicationSettings,
                           Function<String, Client> remoteClient,
                           ClusterService clusterService,
                           AllocationService allocationService,
                           MetadataUpgradeService metadataUpgradeService) {
        this.settings = settings;
        this.threadPool = threadPool;
        this.replicationService = replicationService;
        this.replicationSettings = replicationSettings;
        this.remoteClient = remoteClient;

        this.clusterService = clusterService;
        this.indexScopedSettings = indexScopedSettings;
        this.allocationService = allocationService;
        this.metadataUpgradeService = metadataUpgradeService;
    }

    private void start() {
        assert isActive == false : "MetadataTracker is already started";
        assert clusterService.state().nodes().isLocalNodeElectedMaster() : "MetadataTracker must only be run on the master node";
        RetryRunnable runnable = new RetryRunnable(
            threadPool,
            ThreadPool.Names.LOGICAL_REPLICATION,
            this::run,
            BackoffPolicy.exponentialBackoff(replicationSettings.pollDelay(), 8)
        );
        cancellable = runnable;
        isActive = true;
        runnable.run();
    }

    private void stop() {
        isActive = false;
        Cancellable currentCancellable = cancellable;
        if (currentCancellable != null) {
            currentCancellable.cancel();
        }
    }

    private void schedule() {
        if (!isActive) {
            return;
        }

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Reschedule tracking metadata");
        }
        cancellable = threadPool.scheduleUnlessShuttingDown(
            replicationSettings.pollDelay(),
            ThreadPool.Names.LOGICAL_REPLICATION,
            this::run
        );
    }

    public void update(Collection<String> subscriptionNames) {
        synchronized (this) {
            if (subscriptionNames.isEmpty()) {
                stop();
            } else {
                subscriptionsToTrack = new HashSet<>(subscriptionNames);
                if (!isActive) {
                    start();
                }
            }
        }
    }

    public boolean stopTracking(String subscriptionName) {
        synchronized (this) {
            var copy = new HashSet<>(subscriptionsToTrack);
            var updated = copy.remove(subscriptionName);
            if (isActive && copy.isEmpty()) {
                stop();
            }
            subscriptionsToTrack = copy;
            return updated;
        }
    }

    private void run() {
        // single volatile read
        var currentSubscriptionsToTrack = subscriptionsToTrack;

        var countDown = new CountdownFuture(currentSubscriptionsToTrack.size());
        countDown.thenRun(this::schedule);

        for (String subscriptionName : currentSubscriptionsToTrack) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Poll metadata for subscription {}", subscriptionName);
            }
            processSubscription(subscriptionName).whenComplete(countDown);
        }
    }

    private CompletableFuture<?> processSubscription(String subscriptionName) {
        final ClusterState subscriberState = clusterService.state();
        var subscription = SubscriptionsMetadata.get(subscriberState.metadata()).get(subscriptionName);
        if (subscription == null) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Subscription {} not found inside current local cluster state", subscriptionName);
            }
            return CompletableFuture.completedFuture(null);
        }
        Client client;
        try {
            client = remoteClient.apply(subscriptionName);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        var request = new PublicationsStateAction.Request(
            subscription.publications(),
            subscription.connectionInfo().user()
        );
        CompletableFuture<Response> publicationsState = client.execute(PublicationsStateAction.INSTANCE, request)
            .thenApply(r ->
                new Response(
                    metadataUpgradeService.upgradeMetadata(r.metadata()),
                    r.unknownPublications()
                ));
        CompletableFuture<Boolean> updatedClusterState = publicationsState.thenCompose(response -> {
            if (response.unknownPublications().containsAll(subscription.publications())) {
                stopTracking(subscriptionName);
                return CompletableFuture.completedFuture(false);
            }

            // We cannot use replicationService.verifyTablesDoNotExist(subscriptionName, stateResponse)
            // as it uses cluster metadata for comparison and will fail on the second round of every replication.
            Set<RelationName> existingTables = getExistingLocallyTables(subscription, subscriberState, response);
            if (!existingTables.isEmpty()) {
                var msg = String.format(
                    Locale.ENGLISH,
                    "Tracking of metadata failed for subscription '" + subscriptionName + "'" + ", stopping tracking. Some relation(s) already exist. " +
                        "Check table pg_subscription_rel to see existing tables.",
                    subscriptionName
                );
                LOGGER.error(msg);
                return replicationService.updateSubscriptionState(
                    subscriptionName,
                    existingTables,
                    Subscription.State.FAILED,
                    // Table name is not included, as this message included for every row of pg_subscriptions_rel which already has table name.
                    "Relation already exists"
                ).handle((ignoredAck, ignoredErr) -> {
                    stopTracking(subscriptionName);
                    return false;
                });
            } else {
                return updateClusterState(subscriptionName, response);
            }
        });

        return updatedClusterState.thenCompose(acked -> {
            if (!acked) {
                return CompletableFuture.completedFuture(false);
            }
            assert publicationsState.isDone() : "If thenCompose triggers, publicationsState must be done";
            var publicationResponse = publicationsState.join();

            RestoreDiff restoreDiff = getRestoreDiff(subscription, subscriberState, publicationResponse);
            if (restoreDiff.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            CompletableFuture<Boolean> updateState = restoreDiff.relationsForStateUpdate.isEmpty()
                ? CompletableFuture.completedFuture(true)
                : replicationService.updateSubscriptionState(
                    subscriptionName,
                    restoreDiff.relationsForStateUpdate,
                    Subscription.State.INITIALIZING,
                    null
                );

            return updateState.thenCompose(_ -> replicationService.restore(
                subscriptionName,
                subscription.settings(),
                restoreDiff.toRestore
            ));
        }).exceptionallyCompose(err -> {
            var e = SQLExceptions.unwrap(err);
            if (SQLExceptions.maybeTemporary(e)) {
                LOGGER.warn("Retrieving remote metadata failed for subscription '" + subscriptionName + "', will retry", e);
                return CompletableFuture.completedFuture(null);
            }
            if (e instanceof SubscriptionUnknownException) {
                stopTracking(subscriptionName);
                return CompletableFuture.completedFuture(null);
            }
            var msg = "Tracking of metadata failed for subscription '" + subscriptionName + "'" + " with unrecoverable error, stop tracking";
            LOGGER.error(msg, e);
            return replicationService.updateSubscriptionState(subscriptionName, Subscription.State.FAILED, msg + ".\nReason: " + e.getMessage())
                .handle((ignoredAck, ignoredErr) -> {
                    stopTracking(subscriptionName);
                    return null;
                });
        });
    }

    private CompletableFuture<Boolean> updateClusterState(String subscriptionName, Response response) {
        var updateTask = new AckedClusterStateUpdateTask<AcknowledgedResponse>(new AckMetadataUpdateRequest()) {

            @Override
            public ClusterState execute(ClusterState localClusterState) throws Exception {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Process cluster state for subscription {}", subscriptionName);
                }

                Metadata localMetadata = localClusterState.metadata();
                Subscription subscription = SubscriptionsMetadata.get(localMetadata).get(subscriptionName);
                if (subscription == null) {
                    LOGGER.warn("Subscription {} disappeared", subscriptionName);
                    return localClusterState;
                }

                localClusterState = processDroppedTablesOrPartitions(
                    subscriptionName,
                    subscription,
                    localClusterState,
                    response.metadata()
                );
                return updateRelations(
                    subscriptionName,
                    subscription,
                    localClusterState,
                    response,
                    indexScopedSettings
                );
            }

            @Override
            protected AcknowledgedResponse newResponse(boolean acknowledged) {
                return new AcknowledgedResponse(acknowledged);
            }
        };
        clusterService.submitStateUpdateTask("track-metadata", updateTask);
        return updateTask.completionFuture().thenApply(AcknowledgedResponse::isAcknowledged);
    }

    private static class AckMetadataUpdateRequest extends AcknowledgedRequest<AckMetadataUpdateRequest> {
    }

    @VisibleForTesting
    static ClusterState updateRelations(String subscriptionName,
                                        Subscription subscription,
                                        ClusterState subscriberClusterState,
                                        Response publicationsState,
                                        IndexScopedSettings indexScopedSettings) {
        // Check for all the subscribed tables if the index metadata and settings changed and if so apply
        // the changes from the publisher cluster state to the subscriber cluster state
        var updatedMetadataBuilder = Metadata.builder(subscriberClusterState.metadata());
        var updateClusterState = false;
        Metadata subscriberMetadata = subscriberClusterState.metadata();
        Metadata publisherMetadata = publicationsState.metadata();
        for (var followedTable : subscription.relations().keySet()) {
            RelationMetadata.Table publisherTable = publisherMetadata.getRelation(followedTable);
            if (publisherTable == null) {
                // Dropped relations are handled separately, see processDroppedTablesOrPartitions(...)
                continue;
            }
            RelationMetadata.Table subscriberTable = subscriberMetadata.getRelation(followedTable);
            if (subscriberTable == null) {
                // Table creation is handled by restore, see getRestoreDiff(...)
                continue;
            }

            // Update main table relation
            if (publisherTable.tableVersion() > subscriberTable.tableVersion()) {
                Settings updatedSettings = updateSettings(
                    publisherTable.settings(),
                    subscriberTable.settings(),
                    indexScopedSettings
                );
                updatedMetadataBuilder.setTable(
                    subscriberTable.name(),
                    publisherTable.columns(),
                    updatedSettings != null ? updatedSettings : subscriberTable.settings(),
                    publisherTable.routingColumn(),
                    publisherTable.columnPolicy(),
                    publisherTable.pkConstraintName(),
                    publisherTable.checkConstraints(),
                    publisherTable.primaryKeys(),
                    publisherTable.partitionedBy(),
                    subscriberTable.state(),
                    subscriberTable.indexUUIDs(),
                    publisherTable.tableVersion()
                );
                updateClusterState = true;
            }

            // Update table indices, but only indices existing already on the subscriber.
            Map<String, IndexMetadata> subscriberIndices = subscriberMetadata.getIndices(followedTable, List.of(), false, x -> x)
                    .stream()
                    .collect(Collectors.toMap(x -> PUBLISHER_INDEX_UUID.get(x.getSettings()), x -> x));
            List<IndexMetadata> publisherIndices = publisherMetadata.getIndices(followedTable, List.of(), false, x -> x);

            for (IndexMetadata publisherIndexMetadata : publisherIndices) {
                var subscriberIndexMetadata = subscriberIndices.get(publisherIndexMetadata.getIndexUUID());
                if (subscriberIndexMetadata != null) {
                    var updatedIndexMetadataBuilder = IndexMetadata.builder(subscriberIndexMetadata);
                    var updatedMapping = updateIndexMetadataMappings(publisherIndexMetadata, subscriberIndexMetadata);
                    if (updatedMapping != null) {
                        updatedIndexMetadataBuilder.putMapping(updatedMapping).mappingVersion(publisherIndexMetadata.getMappingVersion());
                    }
                    var updatedSettings = updateSettings(
                        publisherIndexMetadata.getSettings(),
                        subscriberIndexMetadata.getSettings(),
                        indexScopedSettings
                    );
                    if (updatedSettings != null) {
                        updatedIndexMetadataBuilder.settings(updatedSettings).settingsVersion(subscriberIndexMetadata.getSettingsVersion() + 1L);
                    }
                    if (updatedMapping != null || updatedSettings != null) {
                        IndexMetadata indexMetadata = updatedIndexMetadataBuilder.build();
                        updatedMetadataBuilder.put(indexMetadata, true);
                        updateClusterState = true;
                    }
                }
            }
        }

        if (updateClusterState) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Updated some relations of subscription {}", subscriptionName);
            }
            return ClusterState.builder(subscriberClusterState).metadata(updatedMetadataBuilder).build();
        } else {
            return subscriberClusterState;
        }
    }

    /**
     * @return empty list if there is no validation error and all tables don't exist locally
     * or list of existing tables otherwise.
     * These tables will be reported
     * and will be visible in pg_subscriptions_rel table.
     */
    private static Set<RelationName> getExistingLocallyTables(Subscription subscription,
                                                               ClusterState subscriberClusterState,
                                                               Response publisherStateResponse) {
        // Table existence check is done on a subscription creation but
        // there are cases when attempt to subscribe to existing table happens later during the replication.
        var metadata = subscriberClusterState.metadata();
        Set<RelationName> currentlyReplicatedTables = subscription.relations().keySet();

        return publisherStateResponse.metadata().relations(RelationMetadata.Table.class).stream()
            .map(RelationMetadata.Table::name)
            .filter(relationName -> metadata.getRelation(relationName) != null)
            .filter(relationName -> currentlyReplicatedTables.contains(relationName) == false)
            .collect(Collectors.toSet());
    }

    record RestoreDiff(List<TableOrPartition> toRestore,
                       Set<RelationName> relationsForStateUpdate) {

        public boolean isEmpty() {
            return toRestore.isEmpty();
        }
    }

    @VisibleForTesting
    static RestoreDiff getRestoreDiff(Subscription subscription,
                                      ClusterState subscriberState,
                                      PublicationsStateAction.Response stateResponse) {
        Map<RelationName, RelationState> subscribedRelations = subscription.relations();
        HashSet<RelationName> relationNamesForStateUpdate = new HashSet<>();
        HashSet<TableOrPartition> toRestore = new HashSet<>();
        Metadata subscriberMetadata = subscriberState.metadata();
        Metadata publisherMetadata = stateResponse.metadata();
        for (RelationMetadata.Table table : publisherMetadata.relations(RelationMetadata.Table.class)) {
            RelationName relationName = table.name();
            for (IndexMetadata indexMetadata : publisherMetadata.getIndices(relationName, List.of(), false, x -> x)) {
                String indexName = indexMetadata.getIndex().getName();
                if (subscribedRelations.get(relationName) == null) {
                    relationNamesForStateUpdate.add(relationName);
                }
                if (REPLICATION_INDEX_ROUTING_ACTIVE.get(indexMetadata.getSettings()) == false) {
                    // If the index is not active, we cannot restore it
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Skipping index {} for subscription {} as it is not active", indexName, subscription);
                    }
                    continue;
                }
                String indexUUID = subscriberMetadata.getIndex(relationName, indexMetadata.partitionValues(), false, IndexMetadata::getIndexUUID);
                if (indexUUID == null) {
                    String partitionIdent = PartitionName.encodeIdent(indexMetadata.partitionValues());
                    toRestore.add(new TableOrPartition(relationName, partitionIdent));
                    relationNamesForStateUpdate.add(relationName);
                }
            }
            if (table.indexUUIDs().isEmpty() && table.partitionedBy().isEmpty() == false) {
                // If the table is partitioned, we need to restore the table itself
                if (subscriberMetadata.getRelation(relationName) == null) {
                    toRestore.add(new TableOrPartition(relationName, null));
                    relationNamesForStateUpdate.add(relationName);
                }
            }
        }
        if (toRestore.isEmpty()) {
            relationNamesForStateUpdate.clear();
        }
        return new RestoreDiff(toRestore.stream().toList(), relationNamesForStateUpdate);
    }

    private ClusterState processDroppedTablesOrPartitions(String subscriptionName,
                                                          Subscription subscription,
                                                          ClusterState subscriberClusterState,
                                                          Metadata publisherMetadata) {
        HashSet<RelationName> changedRelations = new HashSet<>();
        HashSet<Index> partitionsToRemove = new HashSet<>();
        Metadata subscriberMetadata = subscriberClusterState.metadata();
        Metadata.Builder updatedMetadataBuilder = Metadata.builder(subscriberMetadata);
        for (var relationName : subscription.relations().keySet()) {
            RelationMetadata.Table publisherTable = publisherMetadata.getRelation(relationName);
            if (publisherTable == null) {
                changedRelations.add(relationName);
                continue;
            }
            RelationMetadata.Table subscriberTable = subscriberMetadata.getRelation(relationName);
            if (subscriberTable == null) {
                continue;
            }
            // Check for possible dropped partitions
            List<IndexMetadata> concreteIndices = subscriberMetadata.getIndices(relationName, List.of(), false, x -> x);
            for (IndexMetadata concreteIndex : concreteIndices) {
                String indexUUID = PUBLISHER_INDEX_UUID.get(concreteIndex.getSettings());
                boolean publisherContainsIndex = publisherTable.indexUUIDs().contains(indexUUID);
                if (!publisherContainsIndex) {
                    partitionsToRemove.add(concreteIndex.getIndex());
                }
            }
        }

        var updatedClusterState = ClusterState.builder(subscriberClusterState)
            .metadata(updatedMetadataBuilder)
            .build();

        if (partitionsToRemove.isEmpty() == false) {
            updatedClusterState = MetadataDeleteIndexService.deleteIndices(
                updatedClusterState,
                settings,
                allocationService,
                partitionsToRemove
            );

        }
        if (changedRelations.isEmpty() == false) {
            HashMap<RelationName, Subscription.RelationState> relations = new HashMap<>();
            for (var entry : subscription.relations().entrySet()) {
                var relationName = entry.getKey();
                if (changedRelations.contains(relationName) == false) {
                    RelationState state = entry.getValue();
                    relations.put(relationName, state);
                }
            }
            updatedClusterState = DropSubscriptionAction.removeSubscriptionSetting(
                changedRelations,
                updatedClusterState,
                Metadata.builder(updatedClusterState.metadata())
            );

            updatedClusterState = UpdateSubscriptionAction.update(
                updatedClusterState,
                subscriptionName,
                new Subscription(
                    subscription.owner(),
                    subscription.connectionInfo(),
                    subscription.publications(),
                    subscription.settings(),
                    relations
                )
            );
        }
        return updatedClusterState;
    }

    @Nullable
    private static MappingMetadata updateIndexMetadataMappings(IndexMetadata publisherIndexMetadata,
                                                               IndexMetadata subscriberIndexMetadata) {
        var publisherMapping = publisherIndexMetadata.mapping();
        var subscriberMapping = subscriberIndexMetadata.mapping();
        if (publisherMapping != null && subscriberMapping != null) {
            if (publisherIndexMetadata.getMappingVersion() > subscriberIndexMetadata.getMappingVersion()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Updated subscriber mapping index={}, newMapping={}",
                        subscriberIndexMetadata.getIndex().getName(),
                        publisherMapping.sourceAsMap());
                }
                return publisherMapping;
            }
        }
        return null;
    }

    @Nullable
    private static Settings updateSettings(Settings publisherSettings,
                                           Settings subscriberSettings,
                                           IndexScopedSettings indexScopedSettings) {
        var updatedSettings = publisherSettings.filter(
            key -> isReplicatableSetting(key, indexScopedSettings) &&
                !Objects.equals(subscriberSettings.get(key), publisherSettings.get(key)));
        if (updatedSettings.isEmpty()) {
            return null;
        }
        return Settings.builder()
            .put(subscriberSettings)
            .put(updatedSettings)
            .build();
    }

    private static boolean isReplicatableSetting(String key, IndexScopedSettings indexScopedSettings) {
        var setting = indexScopedSettings.get(key);
        return setting != null &&
            !setting.isInternalIndex() &&
            !setting.isPrivateIndex() &&
            indexScopedSettings.isDynamicSetting(key) &&
            !indexScopedSettings.isPrivateSetting(key) &&
            !NON_REPLICATED_SETTINGS.contains(setting);
    }

    @Override
    public void close() {
        stop();
    }

    @VisibleForTesting
    public boolean isActive() {
        return isActive;
    }
}

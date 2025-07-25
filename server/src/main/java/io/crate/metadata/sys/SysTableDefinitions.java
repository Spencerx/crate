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

package io.crate.metadata.sys;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import org.elasticsearch.cluster.RestoreInProgress;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.repositories.RepositoriesService;

import io.crate.execution.engine.collect.files.SummitsIterable;
import io.crate.execution.engine.collect.stats.JobsLogs;
import io.crate.expression.reference.StaticTableDefinition;
import io.crate.expression.reference.sys.check.SysCheck;
import io.crate.expression.reference.sys.check.SysChecker;
import io.crate.expression.reference.sys.check.node.SysNodeChecks;
import io.crate.expression.reference.sys.shard.ShardSegments;
import io.crate.expression.reference.sys.shard.SysAllocations;
import io.crate.expression.reference.sys.snapshot.SysSnapshots;
import io.crate.metadata.RelationName;
import io.crate.metadata.SystemTable;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.role.Securable;
import io.crate.role.metadata.SysPrivilegesTableInfo;
import io.crate.role.metadata.SysRolesTableInfo;
import io.crate.role.metadata.SysUsersTableInfo;
import io.crate.session.Sessions;

public class SysTableDefinitions {

    private final Map<RelationName, StaticTableDefinition<?>> tableDefinitions;

    @SuppressWarnings("unchecked")
    @Inject
    public SysTableDefinitions(ClusterService clusterService,
                               Roles roles,
                               JobsLogs jobsLogs,
                               SysSchemaInfo sysSchemaInfo,
                               Set<SysCheck> sysChecks,
                               SysNodeChecks sysNodeChecks,
                               RepositoriesService repositoriesService,
                               SysSnapshots sysSnapshots,
                               SysAllocations sysAllocations,
                               ShardSegments shardSegmentInfos,
                               Sessions sessions) {
        Supplier<DiscoveryNode> localNode = clusterService::localNode;
        var sysClusterTableInfo = (SystemTable<Void>) sysSchemaInfo.getTableInfo(SysClusterTableInfo.IDENT.name());
        assert sysClusterTableInfo != null : "sys.cluster table must exist in sys schema";

        SystemTable<Role> userTable = SysUsersTableInfo.create(() -> clusterService.state().metadata().clusterUUID());
        SysChecker<SysCheck> sysChecker = new SysChecker<>(sysChecks);
        SummitsIterable summits = new SummitsIterable();

        tableDefinitions = Map.ofEntries(
            Map.entry(
                SysUsersTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(roles.roles().stream().filter(Role::isUser).toList()),
                    userTable.expressions(),
                    false
                )
            ),
            Map.entry(
                SysRolesTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(roles.roles().stream().filter(r -> r.isUser() == false).toList()),
                    SysRolesTableInfo.INSTANCE.expressions(),
                    false
                )
            ),
            Map.entry(
                SysPrivilegesTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(SysPrivilegesTableInfo.buildPrivilegesRows(roles.roles())),
                    SysPrivilegesTableInfo.INSTANCE.expressions(),
                    false
                )
            ),
            Map.entry(
                SysClusterTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(Collections.singletonList(null)), sysClusterTableInfo.expressions(), false)
            ),
            Map.entry(
                SysJobsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    (_, user) -> completedFuture(
                        () -> StreamSupport.stream(jobsLogs.activeJobs().spliterator(), false)
                            .filter(x ->
                                user.isSuperUser()
                                || user.name().equals(x.username())
                                || roles.hasALPrivileges(user))
                            .iterator()
                    ),
                    SysJobsTableInfo.create(localNode).expressions(),
                    false
                )
            ),
            Map.entry(
                SysJobsLogTableInfo.IDENT,
                new StaticTableDefinition<>(
                    (_, user) -> completedFuture(
                        () -> StreamSupport.stream(jobsLogs.jobsLog().spliterator(), false)
                            .filter(x ->
                                user.isSuperUser()
                                || user.name().equals(x.username())
                                || roles.hasALPrivileges(user))
                            .iterator()
                    ),
                    SysJobsLogTableInfo.create(localNode).expressions(),
                    false)
            ),
            Map.entry(
                SysOperationsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(jobsLogs.activeOperations()),
                    SysOperationsTableInfo.create(localNode).expressions(),
                    false)
            ),
            Map.entry(
                SysOperationsLogTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(jobsLogs.operationsLog()),
                    SysOperationsLogTableInfo.INSTANCE.expressions(),
                    false
                )
            ),
            Map.entry(
                SysChecksTableInfo.IDENT,
                new StaticTableDefinition<>(
                    sysChecker::computeResultAndGet, SysChecksTableInfo.INSTANCE.expressions(), true)
            ),
            Map.entry(
                SysNodeChecksTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(sysNodeChecks), SysNodeChecksTableInfo.INSTANCE.expressions(), true)
            ),
            Map.entry(
                SysRepositoriesTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(repositoriesService.getRepositoriesList()),
                    SysRepositoriesTableInfo.create(clusterService.getClusterSettings().maskedSettings()).expressions(),
                    false
                )
            ),
            Map.entry(
                SysSnapshotsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    sysSnapshots::currentSnapshots, SysSnapshotsTableInfo.INSTANCE.expressions(), true)
            ),
            Map.entry(
                SysSnapshotRestoreTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(SysSnapshotRestoreTableInfo.snapshotsRestoreInProgress(
                        clusterService.state().custom(RestoreInProgress.TYPE),
                        clusterService.state())),
                    SysSnapshotRestoreTableInfo.INSTANCE.expressions(),
                    true
                )
            ),

            Map.entry(
                SysAllocationsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> sysAllocations,
                    (user, allocation) -> roles.hasAnyPrivilege(user, Securable.TABLE, allocation.fqn()),
                    SysAllocationsTableInfo.INSTANCE.expressions()
                )
            ),

            Map.entry(
                SysSummitsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(summits), SysSummitsTableInfo.INSTANCE.expressions(), false)
            ),

            Map.entry(
                SysHealth.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(TableHealth.compute(clusterService.state())),
                    SysHealth.INSTANCE.expressions(),
                    (user, tableHealth) -> roles.hasAnyPrivilege(user, Securable.TABLE, tableHealth.fqn()),
                    true
                )
            ),
            Map.entry(
                SysClusterHealth.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(SysClusterHealth.compute(clusterService.state(), clusterService.getMasterService().numberOfPendingTasks())),
                    SysClusterHealth.INSTANCE.expressions(),
                    false
                )
            ),
            Map.entry(
                SysMetricsTableInfo.NAME,
                new StaticTableDefinition<>(
                    () -> completedFuture(jobsLogs.metrics()), SysMetricsTableInfo.create(localNode).expressions(), false)
            ),
            Map.entry(
                SysSegmentsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    () -> completedFuture(shardSegmentInfos),
                    SysSegmentsTableInfo.create(clusterService::localNode).expressions(),
                    true
                )
            ),
            Map.entry(
                SysSessionsTableInfo.IDENT,
                new StaticTableDefinition<>(
                    (_, user) -> completedFuture(
                        StreamSupport.stream(sessions.getActive().spliterator(), false)
                            .filter(session -> session.isSystemSession() == false
                                && (roles.hasALPrivileges(user)
                                    || session.sessionSettings().sessionUser().equals(user)))
                        .toList()),
                    SysSessionsTableInfo.create(clusterService::localNode).expressions(),
                    false)
            )
        );
    }

    public StaticTableDefinition<?> get(RelationName relationName) {
        return tableDefinitions.get(relationName);
    }
}

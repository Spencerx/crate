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

package io.crate.execution.engine.collect.sources;

import static io.crate.common.collections.Iterables.sequentialStream;
import static java.util.Collections.emptyList;
import static java.util.stream.Stream.concat;
import static java.util.stream.StreamSupport.stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.jetbrains.annotations.NotNull;

import io.crate.Constants;
import io.crate.common.collections.Iterators;
import io.crate.execution.engine.collect.files.SqlFeatureContext;
import io.crate.execution.engine.collect.files.SqlFeatures;
import io.crate.expression.reference.information.ColumnContext;
import io.crate.fdw.ForeignTable;
import io.crate.fdw.ForeignTablesMetadata;
import io.crate.fdw.ServersMetadata;
import io.crate.fdw.ServersMetadata.Server;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.FulltextAnalyzerResolver;
import io.crate.metadata.IndexName;
import io.crate.metadata.NodeContext;
import io.crate.metadata.PartitionInfo;
import io.crate.metadata.PartitionInfos;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationInfo;
import io.crate.metadata.RelationName;
import io.crate.metadata.RoutineInfo;
import io.crate.metadata.RoutineInfos;
import io.crate.metadata.Schemas;
import io.crate.metadata.blob.BlobSchemaInfo;
import io.crate.metadata.information.InformationSchemaInfo;
import io.crate.metadata.information.UserMappingOptionsTableInfo;
import io.crate.metadata.information.UserMappingsTableInfo.UserMapping;
import io.crate.metadata.pgcatalog.OidHash;
import io.crate.metadata.pgcatalog.PgCatalogSchemaInfo;
import io.crate.metadata.pgcatalog.PgClassTable;
import io.crate.metadata.pgcatalog.PgIndexTable;
import io.crate.metadata.pgcatalog.PgProcTable;
import io.crate.metadata.sys.SysSchemaInfo;
import io.crate.metadata.table.ConstraintInfo;
import io.crate.metadata.table.SchemaInfo;
import io.crate.metadata.table.TableInfo;
import io.crate.metadata.view.ViewInfo;
import io.crate.protocols.postgres.types.PGTypes;
import io.crate.role.Role;
import io.crate.role.Roles;
import io.crate.role.Securable;
import io.crate.types.DataTypes;
import io.crate.types.Regclass;
import io.crate.types.Regproc;

public class InformationSchemaIterables {

    private static final Set<String> IGNORED_SCHEMAS = Set.of(
        InformationSchemaInfo.NAME,
        SysSchemaInfo.NAME,
        BlobSchemaInfo.NAME,
        PgCatalogSchemaInfo.NAME);

    private final Iterable<RelationInfo> relations;
    private final Iterable<TableInfo> tables;
    private final Iterable<ViewInfo> views;
    private final PartitionInfos partitionInfos;
    private final Iterable<ColumnContext> columns;
    private final Iterable<RelationInfo> primaryKeys;
    private final Iterable<ConstraintInfo> constraints;
    private final Iterable<ConstraintInfo> pgConstraints;
    private final Iterable<Void> referentialConstraints;
    private final Iterable<PgIndexTable.Entry> pgIndices;
    private final Iterable<PgClassTable.Entry> pgClasses;
    private final Iterable<PgProcTable.Entry> pgTypeReceiveFunctions;
    private final Iterable<PgProcTable.Entry> pgTypeSendFunctions;
    private final NodeContext nodeCtx;
    private final ClusterService clusterService;
    private final RoutineInfos routineInfos;
    private final Schemas schemas;


    @Inject
    public InformationSchemaIterables(NodeContext nodeCtx,
                                      FulltextAnalyzerResolver fulltextAnalyzerResolver,
                                      ClusterService clusterService) {
        this.clusterService = clusterService;
        this.nodeCtx = nodeCtx;
        this.schemas = nodeCtx.schemas();
        views = () -> viewsStream(schemas).iterator();
        tables = () -> tablesStream(schemas).iterator();
        relations = () -> {
            Metadata metadata = clusterService.state().metadata();
            ForeignTablesMetadata foreignTables = metadata.custom(
                ForeignTablesMetadata.TYPE,
                ForeignTablesMetadata.EMPTY
            );
            return concat(
                concat(tablesStream(schemas), viewsStream(schemas)),
                StreamSupport.stream(foreignTables.spliterator(), false)
            ).iterator();
        };
        primaryKeys = () -> sequentialStream(relations)
            .filter(this::isPrimaryKey)
            .iterator();

        columns = () -> sequentialStream(relations)
            .flatMap(r -> sequentialStream(new ColumnsIterable(r)))
            .iterator();

        Iterable<ConstraintInfo> primaryKeyConstraints = () -> sequentialStream(primaryKeys)
            .map(t -> new ConstraintInfo(
                t,
                t.pkConstraintNameOrDefault(),
                ConstraintInfo.Type.PRIMARY_KEY))
            .iterator();

        Iterable<ConstraintInfo> notnullConstraints = () -> sequentialStream(relations)
            .flatMap(r -> sequentialStream(new NotNullConstraintIterable(r)))
            .iterator();

        Iterable<ConstraintInfo> checkConstraints = () ->
            sequentialStream(relations)
                .flatMap(r -> r.checkConstraints()
                    .stream()
                    .map(chk -> new ConstraintInfo(r, chk.name(), ConstraintInfo.Type.CHECK)))
                .iterator();

        constraints = () -> Stream.of(sequentialStream(primaryKeyConstraints),
                                      sequentialStream(notnullConstraints),
                                      sequentialStream(checkConstraints))
            .flatMap(Function.identity())
            .iterator();

        pgConstraints = () -> Stream.of(sequentialStream(primaryKeyConstraints),
            sequentialStream(checkConstraints))
            .flatMap(Function.identity())
            .iterator();

        partitionInfos = new PartitionInfos(clusterService, schemas);
        routineInfos = new RoutineInfos(fulltextAnalyzerResolver, clusterService);

        referentialConstraints = emptyList();

        pgIndices = () -> tablesStream(schemas).filter(this::isPrimaryKey).map(this::pgIndex).iterator();
        pgClasses = () -> concat(sequentialStream(relations).map(this::relationToPgClassEntry),
                                 sequentialStream(primaryKeys).map(this::primaryKeyToPgClassEntry)).iterator();
        pgTypeReceiveFunctions = () ->
            Stream.concat(
                sequentialStream(PGTypes.pgTypes())
                    .filter(t -> t.typArray() != 0)
                    .map(x -> x.typReceive().asDummySignature())
                    .map(PgProcTable.Entry::of),

                // Don't generate array_recv entry from pgTypes to avoid duplicate entries
                // (We want 1 array_recv entry, not one per array type)
                Stream.of(PgProcTable.Entry.of(Regproc.of("array_recv").asDummySignature()))
            )
            .iterator();

        pgTypeSendFunctions = () ->
            Stream.concat(
                sequentialStream(PGTypes.pgTypes())
                    .filter(t -> t.typArray() != 0)
                    .map(x -> x.typSend().asDummySignature())
                    .map(PgProcTable.Entry::of),

                // Don't generate array_send entry from pgTypes to avoid duplicate entries
                // (We want 1 array_send entry, not one per array type)
                Stream.of(PgProcTable.Entry.of(Regproc.of("array_send").asDummySignature()))
            )
            .iterator();
    }


    private boolean isPrimaryKey(RelationInfo relationInfo) {
        return (relationInfo.primaryKey().size() > 1 ||
            (relationInfo.primaryKey().size() == 1 &&
            !relationInfo.primaryKey().get(0).name().equals("_id")));
    }

    private PgClassTable.Entry relationToPgClassEntry(RelationInfo info) {
        return new PgClassTable.Entry(
            Regclass.relationOid(info),
            OidHash.schemaOid(info.ident().schema()),
            info.ident(),
            info.ident().name(),
            toEntryType(info.relationType()),
            info.rootColumns().size(),
            !info.primaryKey().isEmpty());
    }

    private PgClassTable.Entry primaryKeyToPgClassEntry(RelationInfo info) {
        return new PgClassTable.Entry(
            Regclass.primaryOid(info),
            OidHash.schemaOid(info.ident().schema()),
            info.ident(),
            info.pkConstraintNameOrDefault(),
            PgClassTable.Entry.Type.INDEX,
            info.rootColumns().size(),
            !info.primaryKey().isEmpty());
    }

    private static PgClassTable.Entry.Type toEntryType(RelationInfo.RelationType type) {
        return switch (type) {
            case BASE_TABLE -> PgClassTable.Entry.Type.RELATION;
            case VIEW -> PgClassTable.Entry.Type.VIEW;
            case FOREIGN -> PgClassTable.Entry.Type.FOREIGN;
        };
    }

    private PgIndexTable.Entry pgIndex(TableInfo tableInfo) {
        var primaryKey = tableInfo.primaryKey();
        var positions = new ArrayList<Integer>();
        for (var columnIdent : primaryKey) {
            var pkRef = tableInfo.getReference(columnIdent);
            assert pkRef != null : "`getReference(..)` must not return null for columns retrieved from `primaryKey()`";
            var position = pkRef.position();
            positions.add(position);
        }
        return new PgIndexTable.Entry(
            Regclass.relationOid(tableInfo),
            Regclass.primaryOid(tableInfo),
            positions
        );
    }

    private static Stream<ViewInfo> viewsStream(Schemas schemas) {
        return sequentialStream(schemas)
            .flatMap(schema -> sequentialStream(schema.getViews()))
            .filter(i -> !IndexName.isPartitioned(i.ident().indexNameOrAlias()));
    }

    public static Stream<TableInfo> tablesStream(Schemas schemas) {
        return sequentialStream(schemas)
            .flatMap(s -> sequentialStream(s.getTables()));
    }

    public Iterable<SchemaInfo> schemas() {
        return nodeCtx.schemas();
    }

    public Iterable<RelationInfo> relations() {
        return relations;
    }

    public Iterable<TableInfo> tables() {
        return tables;
    }

    public Iterable<PgIndexTable.Entry> pgIndices() {
        return pgIndices;
    }

    public Iterable<ViewInfo> views() {
        return views;
    }

    public Iterable<PartitionInfo> partitions() {
        return partitionInfos;
    }

    public Iterable<ColumnContext> columns() {
        return columns;
    }

    public Iterable<ConstraintInfo> constraints() {
        return constraints;
    }

    public Iterable<ConstraintInfo> pgConstraints() {
        return pgConstraints;
    }

    public Iterable<RoutineInfo> routines() {
        return routineInfos;
    }

    public Iterable<SqlFeatureContext> features() {
        try {
            return SqlFeatures.loadFeatures();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Iterable<PgClassTable.Entry> pgClasses() {
        return pgClasses;
    }

    public Iterable<PgProcTable.Entry> pgProc() {
        return () -> concat(
            sequentialStream(nodeCtx.functions().signatures())
                .map(PgProcTable.Entry::of),
            concat(
                sequentialStream(pgTypeReceiveFunctions),
                sequentialStream(pgTypeSendFunctions)
            )
        ).iterator();
    }

    public Iterable<KeyColumnUsage> keyColumnUsage() {
        return sequentialStream(primaryKeys)
            .filter(tableInfo -> !IGNORED_SCHEMAS.contains(tableInfo.ident().schema()))
            .flatMap(tableInfo -> {
                List<ColumnIdent> pks = tableInfo.primaryKey();
                PrimitiveIterator.OfInt ids = IntStream.range(1, pks.size() + 1).iterator();
                RelationName ident = tableInfo.ident();
                return pks.stream().map(
                    pk -> new KeyColumnUsage(ident, tableInfo.pkConstraintNameOrDefault(), pk, ids.next()));
            })::iterator;
    }


    public Iterable<Void> referentialConstraintsInfos() {
        return referentialConstraints;
    }

    public Iterable<Server> servers() {
        Metadata metadata = clusterService.state().metadata();
        return metadata.custom(ServersMetadata.TYPE, ServersMetadata.EMPTY);
    }

    public Iterable<Server.Option> serverOptions() {
        Metadata metadata = clusterService.state().metadata();
        ServersMetadata servers = metadata.custom(ServersMetadata.TYPE, ServersMetadata.EMPTY);
        return servers.getOptions();
    }

    public Iterable<UserMapping> userMappings() {
        Metadata metadata = clusterService.state().metadata();
        ServersMetadata servers = metadata.custom(ServersMetadata.TYPE, ServersMetadata.EMPTY);
        return servers.getUserMappings();
    }

    public Iterable<UserMappingOptionsTableInfo.UserMappingOptions> userMappingOptions() {
        Metadata metadata = clusterService.state().metadata();
        ServersMetadata servers = metadata.custom(ServersMetadata.TYPE, ServersMetadata.EMPTY);
        return servers.getUserMappingOptions();
    }

    public Iterable<ForeignTable> foreignTables() {
        Metadata metadata = clusterService.state().metadata();
        return metadata.custom(ForeignTablesMetadata.TYPE, ForeignTablesMetadata.EMPTY);
    }

    public Iterable<ForeignTable.Option> foreignTableOptions() {
        Metadata metadata = clusterService.state().metadata();
        ForeignTablesMetadata foreignTables = metadata.custom(
            ForeignTablesMetadata.TYPE,
            ForeignTablesMetadata.EMPTY
        );
        return foreignTables.tableOptions();
    }


    public Iterable<String> enabledRoles(Role role, Roles roles) {
        boolean isAdmin = roles.hasALPrivileges(role);
        return () -> Iterators.concat(
            List.of(role.name()).iterator(),
            applicableRoles(role, roles, isAdmin)
                .map(ApplicableRole::roleName)
                .distinct()
                .iterator()
        );
    }

    public Iterable<ApplicableRole> administrableRoleAuthorizations(Role role, Roles roles) {
        boolean isAdmin = roles.hasALPrivileges(role);
        return () -> applicableRoles(role, roles, isAdmin).filter(ApplicableRole::isGrantable).iterator();
    }

    public Iterable<ApplicableRole> applicableRoles(Role role, Roles roles) {
        boolean isAdmin = roles.hasALPrivileges(role);
        return () -> applicableRoles(role, roles, isAdmin).iterator();
    }

    private Stream<ApplicableRole> applicableRoles(Role role, Roles roles, boolean isAdmin) {
        return role.grantedRoles().stream()
            .mapMulti((grantedRole, c) -> {
                c.accept(new ApplicableRole(role.name(), grantedRole.roleName(), false));
                c.accept(new ApplicableRole(grantedRole.grantor(), grantedRole.roleName(), isAdmin));
                Role retrievedRole = roles.findRole(grantedRole.roleName());
                if (retrievedRole != null) {
                    applicableRoles(retrievedRole, roles, isAdmin)
                        .forEach(c);
                }
            });
    }

    public Iterable<RoleTableGrant> roleTableGrants(Role role, Roles roles) {
        boolean isAdmin = roles.hasALPrivileges(role);
        return () -> roleTableGrants(role, role, roles, isAdmin).distinct().iterator();
    }

    private Stream<RoleTableGrant> roleTableGrants(Role user, Role role, Roles roles, boolean isAdmin) {
        Stream<RoleTableGrant> roleStream = StreamSupport.stream(role.privileges().spliterator(), false)
            .mapMulti((p, c) -> {
                Securable securableType = p.subject().securable();
                // Verify the privilege is valid by any role in the hierarchy
                // (there could a DENY permission making this privilege invalid)
                if (roles.hasPrivilege(user, p.subject().permission(), securableType, p.subject().ident()) == false) {
                    return;
                }
                switch (securableType) {
                    case Securable.TABLE,
                         Securable.VIEW -> {
                        var fqn = p.subject().ident();
                        assert fqn != null : "fqn must not be null for securable type TABLE";
                        RelationName ident = RelationName.fromIndexName(fqn);
                        c.accept(new RoleTableGrant(
                            p.grantor(),
                            role.name(),
                            Constants.DB_NAME,
                            ident.schema(),
                            ident.name(),
                            p.subject().permission().name(),
                            isAdmin,
                            false
                        ));
                    }
                    case SCHEMA -> {
                        SchemaInfo schemaInfo = schemas.getSchemaInfo(p.subject().ident());
                        if (schemaInfo != null) {
                            schemaInfo.getTables().forEach(tableInfo -> c.accept(new RoleTableGrant(
                                p.grantor(),
                                role.name(),
                                Constants.DB_NAME,
                                schemaInfo.name(),
                                tableInfo.ident().name(),
                                p.subject().permission().name(),
                                isAdmin,
                                false
                            )));
                        }
                    }
                    default -> {
                    }
                }
            });
        Stream<RoleTableGrant> parentsStream = role.grantedRoles().stream()
            .mapMulti((grantedRole, c) -> {
                Role retrievedRole = roles.findRole(grantedRole.roleName());
                if (retrievedRole != null) {
                    roleTableGrants(user, retrievedRole, roles, isAdmin)
                        .forEach(c);
                }
            });
        return Stream.concat(roleStream, parentsStream);
    }

    /**
     * Iterable for extracting not null constraints from table info.
     */
    static class NotNullConstraintIterable implements Iterable<ConstraintInfo> {

        private final RelationInfo info;

        NotNullConstraintIterable(RelationInfo info) {
            this.info = info;
        }

        @Override
        @NotNull
        public Iterator<ConstraintInfo> iterator() {
            return new NotNullConstraintIterator(info);
        }
    }

    /**
     * Iterator that returns ConstraintInfo for each TableInfo with not null constraints.
     */
    static class NotNullConstraintIterator implements Iterator<ConstraintInfo> {
        private final RelationInfo relationInfo;
        private final Iterator<Reference> notNullableColumns;

        NotNullConstraintIterator(RelationInfo relationInfo) {
            this.relationInfo = relationInfo;
            notNullableColumns = stream(relationInfo.spliterator(), false)
                .filter(reference -> reference.column().isSystemColumn() == false &&
                                     reference.valueType() != DataTypes.NOT_SUPPORTED &&
                                     reference.isNullable() == false)
                .iterator();
        }

        @Override
        public boolean hasNext() {
            return notNullableColumns.hasNext();
        }

        @Override
        public ConstraintInfo next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Not null constraint iterator exhausted");
            }

            // Building not null constraint name with following pattern:
            // {table_schema}_{table_name}_{column_name}_not_null
            // Currently the longest not null constraint of information_schema
            // is 56 characters long, that's why default string length is set to
            // 60.
            String constraintName =
                this.relationInfo.ident().schema() +
                "_" +
                this.relationInfo.ident().name() +
                "_" +
                this.notNullableColumns.next().column().name() +
                "_not_null";

            // Return nullable columns instead.
            return new ConstraintInfo(
                this.relationInfo,
                constraintName,
                ConstraintInfo.Type.CHECK
            );
        }
    }

    static class ColumnsIterable implements Iterable<ColumnContext> {

        private final RelationInfo relationInfo;

        ColumnsIterable(RelationInfo relationInfo) {
            this.relationInfo = relationInfo;
        }

        @Override
        @NotNull
        public Iterator<ColumnContext> iterator() {
            return new ColumnsIterator(relationInfo);
        }

    }

    static class ColumnsIterator implements Iterator<ColumnContext> {

        private final Iterator<Reference> columns;
        private final RelationInfo tableInfo;

        ColumnsIterator(RelationInfo tableInfo) {
            columns = Stream.concat(stream(tableInfo.spliterator(), false), tableInfo.droppedColumns().stream())
                .filter(reference -> !reference.column().isSystemColumn()
                                     && reference.valueType() != DataTypes.NOT_SUPPORTED).iterator();
            this.tableInfo = tableInfo;
        }

        @Override
        public boolean hasNext() {
            return columns.hasNext();
        }

        @Override
        public ColumnContext next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Columns iterator exhausted");
            }
            return new ColumnContext(tableInfo, columns.next());
        }
    }

    public record KeyColumnUsage(RelationName relationName, String pkName, ColumnIdent pkColumnIdent, int ordinal) {

        public String getSchema() {
            return relationName.schema();
        }

        public String getTableName() {
            return relationName.name();
        }

        public String getFQN() {
            return relationName.fqn();
        }
    }

    public record ApplicableRole(String grantee, String roleName, boolean isGrantable) {}

    public record RoleTableGrant(String grantor, String grantee, String tableCatalog,
                                 String tableSchema, String tableName, String privilegeType,
                                 boolean isGrantable, boolean withHierarchy) {}
}

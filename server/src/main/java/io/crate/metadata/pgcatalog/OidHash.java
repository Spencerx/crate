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

package io.crate.metadata.pgcatalog;

import static org.apache.lucene.util.StringHelper.murmurhash3_x86_32;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jetbrains.annotations.VisibleForTesting;
import io.crate.common.collections.Lists;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.FunctionName;
import io.crate.metadata.RelationInfo;
import io.crate.metadata.RelationName;
import io.crate.metadata.functions.Signature;
import io.crate.replication.logical.metadata.Publication;
import io.crate.replication.logical.metadata.Subscription;
import io.crate.types.TypeSignature;

public final class OidHash {

    private OidHash() {}

    public enum Type {
        SCHEMA,
        TABLE,
        VIEW,
        CONSTRAINT,
        PRIMARY_KEY,
        PROC,
        INDEX,
        USER,
        PUBLICATION,
        SUBSCRIPTION,
        HOST;

        public static Type fromRelationType(RelationInfo.RelationType type) {
            return switch (type) {
                case BASE_TABLE, FOREIGN -> OidHash.Type.TABLE;
                case VIEW -> OidHash.Type.VIEW;
            };
        }
    }

    private static int oid(String key) {
        byte [] b = key.getBytes(StandardCharsets.UTF_8);
        return murmurhash3_x86_32(b, 0, b.length, 0);
    }

    public static int relationOid(RelationInfo relationInfo) {
        Type t = Type.fromRelationType(relationInfo.relationType());
        return oid(t.toString() + relationInfo.ident().fqn());
    }

    public static int relationOid(Type type, RelationName name) {
        return oid(type.toString() + name.fqn());
    }

    public static int schemaOid(String name) {
        return oid(Type.SCHEMA + name);
    }

    public static int primaryKeyOid(RelationName name, List<ColumnIdent> primaryKeys) {
        var primaryKey = Lists.joinOn(" ", primaryKeys, ColumnIdent::name);
        return oid(Type.PRIMARY_KEY + name.fqn() + primaryKey);
    }

    public static int constraintOid(String relationName, String constraintName, String constraintType) {
        return oid(Type.CONSTRAINT + relationName + constraintName + constraintType);
    }

    public static int functionOid(Signature sig) {
        FunctionName name = sig.getName();
        return oid(Type.PROC + name.schema() + name.name() + argTypesToStr(sig.getArgumentTypes()));
    }

    public static int publicationOid(String name, Publication publication) {
        var tables = Lists.joinOn(" ", publication.tables(), RelationName::fqn);
        return oid(Type.PUBLICATION + name + publication.owner() + tables);
    }

    public static int subscriptionOid(String name, Subscription subscription) {
        var publications = String.join(", ", subscription.publications());
        return oid(Type.SUBSCRIPTION + name + subscription.owner() + publications);
    }

    public static int userOid(String name) {
        return oid(Type.USER + name);
    }

    @VisibleForTesting
    static String argTypesToStr(List<TypeSignature> typeSignatures) {
        return Lists.joinOn(" ", typeSignatures, ts -> {
            try {
                return ts.createType().getName();
            } catch (IllegalArgumentException e) {
                // generic signatures, e.g. E, array(E)
                String baseName = ts.getBaseTypeName();
                List<TypeSignature> innerTs = ts.getParameters();
                return baseName + (innerTs.isEmpty() ? "" : "_" + argTypesToStr(innerTs));
            }
        });
    }
}

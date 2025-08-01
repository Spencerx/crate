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

package io.crate.execution.dml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.elasticsearch.Version;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.jetbrains.annotations.NotNull;

import io.crate.exceptions.ConversionException;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Reference;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.server.xcontent.XContentHelper;
import io.crate.types.DataType;

public class RawIndexer {

    private final List<String> partitionValues;
    private final DocTableInfo table;
    private final TransactionContext txnCtx;
    private final NodeContext nodeCtx;
    private final Symbol[] returnValues;
    private final Version shardVersionCreated;

    private final Map<Set<String>, Indexer> indexers = new HashMap<>();
    private final List<Reference> nonDeterministicSynthetics;


    private Indexer currentRowIndexer;
    private IndexItem.StaticItem currentItem;

    public RawIndexer(List<String> partitionValues,
                      DocTableInfo table,
                      Version shardVersionCreated,
                      TransactionContext txnCtx,
                      NodeContext nodeCtx,
                      Symbol[] returnValues,
                      @NotNull List<Reference> nonDeterministicSynthetics) {
        this.partitionValues = partitionValues;
        this.table = table;
        this.txnCtx = txnCtx;
        this.nodeCtx = nodeCtx;
        this.returnValues = returnValues;
        this.nonDeterministicSynthetics = nonDeterministicSynthetics;
        this.shardVersionCreated = shardVersionCreated;
    }

    /**
     * Looks for new columns in the values of the given IndexItem and returns them.
     */
    public List<Reference> collectSchemaUpdates(IndexItem item) throws IOException {
        String raw = (String) item.insertValues()[0];
        Map<String, Object> doc = XContentHelper.convertToMap(JsonXContent.JSON_XCONTENT, raw, true);
        currentRowIndexer = indexers.computeIfAbsent(doc.keySet(), keys -> {
            List<Reference> targetRefs = new ArrayList<>();
            for (String key : keys) {
                ColumnIdent column = ColumnIdent.of(key);
                Reference reference = table.getReference(column);
                if (reference == null) {
                    reference = table.getDynamic(column, true, txnCtx.sessionSettings().errorOnUnknownObjectKey());
                }
                targetRefs.add(reference);
            }

            // Add all non-deterministic synthetics to reflect possible columns/values expansion on replica.
            // item.insertValues might be expanded and we need to reflect that in the target refs.
            // On primary it's an empty list.
            targetRefs.addAll(nonDeterministicSynthetics);

            return new Indexer(
                partitionValues,
                table,
                shardVersionCreated,
                txnCtx,
                nodeCtx,
                targetRefs,
                returnValues
            );
        });

        int numExtra = item.insertValues().length - 1; // First value is _raw.
        assert numExtra == nonDeterministicSynthetics.size() : "Insert columns/values expansion must be done in sync";

        Object[] insertValues = new Object[doc.size() + numExtra];
        List<Reference> columns = currentRowIndexer.columns();
        for (int i = 0; i < doc.size(); i++) {
            Reference reference = columns.get(i);
            // JSON file can have rows with same keys but in different order.
            // We rely on reused currentRowIndexer's target columns order when fetching values from every row.
            Object value = doc.get(reference.column().name()); // Not using FQN, we are fetching only top-level columns.
            DataType<?> type = reference.valueType();
            try {
                insertValues[i] = type.implicitCast(value);
            } catch (ClassCastException | IllegalArgumentException e) {
                throw new ConversionException(value, type);
            }
        }

        // Add synthetics on replica.
        // On primary numExtra = 0;
        for (int i = 0; i < numExtra; i++) {
            insertValues[doc.size() + i] = item.insertValues()[i + 1];
        }

        currentItem = new IndexItem.StaticItem(
            item.id(),
            item.pkValues(),
            insertValues,
            item.seqNo(),
            item.primaryTerm()
        );

        return currentRowIndexer.collectSchemaUpdates(currentItem);
    }

    public void updateTargets(Function<ColumnIdent, Reference> getRef) {
        for (var indexer : indexers.values()) {
            indexer.updateTargets(getRef);
        }
    }

    /**
     * Create a {@link ParsedDocument} from {@link IndexItem}
     *
     * This must be called after any new columns (found via
     * {@link #collectSchemaUpdates(IndexItem)}) have been added to the cluster
     * state.
     */
    public ParsedDocument index() throws IOException {
        assert currentRowIndexer != null : "Must be used only after collecting schema updates.";
        return currentRowIndexer.index(currentItem);
    }

    public Object[] addGeneratedValues(IndexItem item) {
        return currentRowIndexer.addGeneratedValues(item);
    }
}

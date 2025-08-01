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

package io.crate.expression.reference.doc.lucene;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import io.crate.common.collections.Maps;
import io.crate.execution.engine.fetch.ReaderContext;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.DocReferences;
import io.crate.metadata.Reference;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.doc.SysColumns;
import io.crate.sql.tree.ColumnPolicy;
import io.crate.types.ArrayType;
import io.crate.types.ObjectType;

/**
 * Loads values for rows in a table and presents them as a {@link StoredRow}
 */
public abstract class StoredRowLookup implements StoredRow {

    public static final Version PARTIAL_STORED_SOURCE_VERSION = Version.V_5_10_0;

    protected final DocTableInfo table;
    private final List<String> partitionValues;

    protected int doc;
    protected ReaderContext readerContext;
    protected boolean docVisited = false;
    protected Map<String, Object> parsedSource = null;


    public static StoredRowLookup create(Version shardCreatedVersion, DocTableInfo table, List<String> partitionValues) {
        return create(shardCreatedVersion, table, partitionValues, List.of(), false);
    }

    public static StoredRowLookup create(Version shardCreatedVersion, DocTableInfo table, List<String> partitionValues, List<Symbol> columns, boolean fromTranslog) {
        if (shardCreatedVersion.before(PARTIAL_STORED_SOURCE_VERSION) || fromTranslog) {
            return new FullStoredRowLookup(table, partitionValues, columns);
        }
        return new ColumnAndStoredRowLookup(table, shardCreatedVersion, partitionValues, columns);
    }

    private StoredRowLookup(DocTableInfo table, List<String> partitionValues) {
        this.table = table;
        this.partitionValues = partitionValues;
        assert partitionValues == null || partitionValues.size() == table.partitionedBy().size()
            : "PartitionName must have values for each partitionedBy column";
    }

    protected Map<String, Object> injectPartitionValues(Map<String, Object> input) {
        List<ColumnIdent> partitionedBy = table.partitionedBy();
        for (int i = 0; i < partitionedBy.size(); i++) {
            ColumnIdent columnIdent = partitionedBy.get(i);
            Maps.mergeInto(input, columnIdent.name(), columnIdent.path(), partitionValues.get(i));
        }
        return input;
    }

    public final StoredRow getStoredRow(ReaderContext context, int doc) {
        // TODO ideally we could assert here that doc is increasing if the reader context hasn't
        // changed, and then the implementations can always cache their readers.  However, at the
        // moment anything coming via ScoreDocRowFunction may not be in-order, as docs come out
        // of the priority queue in sorted order, not docid order.
        boolean reuseReader = this.readerContext != null
            && this.readerContext.reader() == context.reader()
            && this.doc <= doc;
        if (reuseReader && this.doc == doc) {
            // We haven't moved since the last getStoredRow call, so don't invalidate
            return this;
        }
        this.doc = doc;
        this.readerContext = context;
        try {
            moveToDoc(reuseReader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    protected abstract void moveToDoc(boolean reuseReader) throws IOException;

    protected abstract void registerRef(Reference ref);

    public final void register(List<Symbol> symbols) {
        if (symbols != null && Symbols.hasColumn(symbols, SysColumns.DOC) == false) {
            Consumer<Reference> register = ref -> {
                if (ref.column().isSystemColumn() == false && ref.granularity() == RowGranularity.DOC) {
                    registerRef(DocReferences.toDocLookup(ref));
                }
            };
            for (Symbol symbol : symbols) {
                symbol.visit(Reference.class, register);
            }
        } else {
            registerAll();
        }
    }

    public void registerAll() {
        for (Reference ref : table.rootColumns()) {
            if (ref.column().isSystemColumn() == false & ref.granularity() == RowGranularity.DOC) {
                registerRef(DocReferences.toDocLookup(ref));
            }
        }
    }

    private static class FullStoredRowLookup extends StoredRowLookup {

        private final SourceFieldVisitor fieldsVisitor = new SourceFieldVisitor();
        private final SourceParser sourceParser;

        public FullStoredRowLookup(DocTableInfo table, List<String> partitionValues, List<Symbol> columns) {
            super(table, partitionValues);
            this.sourceParser = new SourceParser(table.lookupNameBySourceKey(), true);
            register(columns);
        }

        @Override
        protected void registerRef(Reference ref) {
            sourceParser.register(ref.column(), ref.valueType());
        }

        @Override
        protected void moveToDoc(boolean reuseReader) {
            fieldsVisitor.reset();
            this.docVisited = false;
            this.parsedSource = null;
        }

        // On build source map, load via StoredFieldsVisitor and pass to SourceParser
        @Override
        public Map<String, Object> asMap() {
            if (parsedSource == null) {
                parsedSource = injectPartitionValues(sourceParser.parse(loadStoredFields()));
            }
            return parsedSource;
        }

        @Override
        public String asRaw() {
            return CompressorFactory.uncompressIfNeeded(loadStoredFields()).utf8ToString();

        }

        private BytesReference loadStoredFields() {
            try {
                if (docVisited == false) {
                    readerContext.visitDocument(doc, fieldsVisitor);
                    docVisited = true;
                }
                return fieldsVisitor.source();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ColumnAndStoredRowLookup extends StoredRowLookup {

        private record ColumnExpression(LuceneCollectorExpression<?> expression, ColumnIdent ident, String storageName) {}

        private final List<ColumnExpression> expressions = new ArrayList<>();
        private final ColumnFieldVisitor fieldsVisitor;

        private ColumnAndStoredRowLookup(DocTableInfo table, Version shardVersionCreated, List<String> partitionValues, List<Symbol> columns) {
            super(table, partitionValues);
            this.fieldsVisitor = new ColumnFieldVisitor(table, shardVersionCreated);
            register(columns);
        }

        @Override
        protected void registerRef(Reference ref) {
            registerRef(ref, false);
        }

        private void registerRef(Reference ref, boolean fromParents) {
            if (fromParents == false) {
                // if we are inside an array, or an object type with ignored fields, then we need to load
                // the relevant parent from stored fields
                var storedParent = table.findParentReferenceMatching(ref, r ->
                    r.valueType() instanceof ObjectType objectType &&
                        objectType.columnPolicy() == ColumnPolicy.IGNORED ||
                        r.valueType() instanceof ArrayType<?>);
                if (storedParent != null) {
                    this.fieldsVisitor.registerRef(storedParent);
                }
            }
            if (ref.hasColumn(SysColumns.DOC) || ref.hasColumn(SysColumns.RAW)) {
                // top-level _doc - we register all table columns
                registerAll();
            } else if (ref.valueType() instanceof ObjectType) {
                this.fieldsVisitor.registerRef(ref);
                for (var leaf : table.getChildReferences(ref)) {
                    registerRef(leaf, true);
                }
            } else if (ref.valueType().storageSupportSafe().retrieveFromStoredFields() || ref.hasDocValues() == false) {
                this.fieldsVisitor.registerRef(ref);
            } else {
                LuceneCollectorExpression<?> expr = LuceneReferenceResolver.typeSpecializedExpression(
                    ref,
                    table.isParentReferenceIgnored()
                );
                assert expr instanceof DocCollectorExpression<?> == false;
                var column = ref.toColumn();
                if (column.isRoot() == false && column.name().equals(SysColumns.Names.DOC)) {
                    column = column.shiftRight();
                }
                expressions.add(new ColumnExpression(expr, column, ref.storageIdent()));
            }
        }

        @Override
        protected void moveToDoc(boolean reuseReader) throws IOException {
            fieldsVisitor.reset();
            this.docVisited = false;
            this.parsedSource = null;
            for (var expr : expressions) {
                if (reuseReader == false) {
                    expr.expression.setNextReader(readerContext);
                }
                expr.expression.setNextDocId(doc);
            }
        }

        @Override
        public Map<String, Object> asMap() {
            if (docVisited == false) {
                try {
                    parsedSource = buildDocMap();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return parsedSource;
        }

        @Override
        public String asRaw() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (XContentBuilder builder = XContentFactory.json(output)) {
                RawFieldVisitor visitor = new RawFieldVisitor();
                readerContext.visitDocument(doc, visitor);
                Map<String, Object> docMap = visitor.getStoredValues();
                for (var expr : expressions) {
                    expr.expression.setNextReader(readerContext);
                    expr.expression.setNextDocId(doc);
                    var value = expr.expression.value();
                    if (value != null) {
                        Maps.mergeInto(docMap, expr.storageName, List.of(), value);
                    }
                }
                builder.map(docMap);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return output.toString(StandardCharsets.UTF_8);
        }

        private Map<String, Object> buildDocMap() throws IOException {
            Map<String, Object> docMap = storedMap();
            for (var expr : expressions) {
                var value = expr.expression.value();
                if (value != null) {
                    Maps.mergeInto(docMap, expr.ident.name(), expr.ident.path(), value);
                }
            }
            docMap = injectPartitionValues(docMap);
            docVisited = true;
            return docMap;
        }

        private Map<String, Object> storedMap() throws IOException {
            if (fieldsVisitor.shouldLoadStoredFields()) {
                readerContext.visitDocument(doc, fieldsVisitor);
                return fieldsVisitor.getDocMap();
            } else {
                return new HashMap<>();
            }
        }
    }
}

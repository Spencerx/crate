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

package io.crate.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.assertj.core.api.AbstractAssert;
import org.elasticsearch.index.mapper.ParsedDocument;

public class ParsedDocumentAssert extends AbstractAssert<ParsedDocumentAssert, ParsedDocument> {

    ParsedDocumentAssert(ParsedDocument actual) {
        super(actual, ParsedDocumentAssert.class);
    }

    public void hasSameFieldsWithNameAs(ParsedDocument expected, String fieldName) {
        hasSameResolvedFields(expected.doc(), fieldName);
    }

    public void hasSameResolvedFields(Document expected, String fieldName) {
        IndexableField[] expectedFields = expected.getFields(fieldName);
        IndexableField[] actualFields = actual.doc().getFields(fieldName);
        assertThat(actualFields).hasSize(expectedFields.length);
        for (int i = 0; i < expectedFields.length; i++) {
            var field1 = actualFields[i];
            var field2 = expectedFields[i];
            assertThat(field1.binaryValue()).as("field " + fieldName).isEqualTo(field2.binaryValue());
            assertThat(field1.stringValue()).as("field " + fieldName).isEqualTo(field2.stringValue());
            assertThat(field1.numericValue()).as("field " + fieldName).isEqualTo(field2.numericValue());
        }
    }

    public void parsesTo(ParsedDocument expected) {
        for (IndexableField f : expected.doc()) {
            hasSameFieldsWithNameAs(expected, f.name());
        }
        for (IndexableField f : actual.doc()) {
            assertThat(expected.doc().getField(f.name())).as(f.name()).isNotNull();
        }
    }
}

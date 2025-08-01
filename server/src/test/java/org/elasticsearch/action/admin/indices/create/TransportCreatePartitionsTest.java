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

package org.elasticsearch.action.admin.indices.create;

import static io.crate.testing.Asserts.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.test.IntegTestCase;
import org.junit.Before;
import org.junit.Test;

import com.carrotsearch.hppc.cursors.ObjectCursor;

import io.crate.metadata.PartitionName;
import io.crate.metadata.RelationName;
import io.crate.testing.UseRandomizedSchema;

@UseRandomizedSchema(random = false)
public class TransportCreatePartitionsTest extends IntegTestCase {

    TransportCreatePartitions action;

    @Before
    public void prepare() {
        action = cluster().getInstance(TransportCreatePartitions.class, cluster().getMasterName());
    }

    @Test
    public void testCreateBulkIndicesSimple() throws Exception {
        execute("create table test (a int, b int) " +
            "partitioned by (a) " +
            "clustered into 1 shards " +
            "with (number_of_replicas=0)");

        ensureYellow();
        Metadata indexMetadata = cluster().clusterService().state().metadata();

        // CREATE TABLE... PARTITIONED BY doesn't create an index, it creates only template via MetadataIndexTemplateService.
        assertThat(indexMetadata.indices()).isEmpty();

        // Inserting some records into a partitioned table leads to creating indices/partitions by TransportCreatePartitionAction.
        execute("insert into test (a,b) values (1,1), (2,2), (3,3)");
        execute("refresh table test");

        Metadata updatedMetadata = cluster().clusterService().state().metadata();
        assertThat(updatedMetadata.indices()).hasSize(3); // 1 table with 3 partitions.

        // Assert number of routing shards is calculated properly to
        // allow for future shard number increase on existing partitions.
        PartitionName partitionName = new PartitionName(new RelationName(sqlExecutor.getCurrentSchema(), "test"),
                                                 List.of(String.valueOf(1)));
        String indexUUID = clusterService().state().metadata()
            .getIndex(partitionName.relationName(), partitionName.values(), true, IndexMetadata::getIndexUUID);

        assertThat(updatedMetadata.index(indexUUID).getRoutingNumShards()).isEqualTo(1024);

        // CREATE TABLE statement assigns specific names to partitioned tables indices, all having template name as a prefix.
        // See BoundCreateTable.templateName
        String tableTemplateName = PartitionName.templateName("doc", "test");

        for (ObjectCursor<IndexMetadata> cursor : updatedMetadata.indices().values()) {
            String indexName = cursor.value.getIndex().getName(); // Something like "partitioned.{table_name}.{part}
            assertThat(PartitionName.templateName(indexName)).isEqualTo(tableTemplateName);
        }
    }

    @Test
    public void test_insert_into_existing_partition_does_not_recreate_it() throws Exception {
        execute("create table table1 (a int, b int) " +
            "partitioned by (a) " +
            "clustered into 1 shards " +
            "with (number_of_replicas=0)");

        ensureYellow();
        Metadata indexMetadata = cluster().clusterService().state().metadata();

        // CREATE TABLE... PARTITIONED BY doesn't create an index, it creates only template via MetadataIndexTemplateService.
        assertThat(indexMetadata.indices()).isEmpty();

        // Create some indices/partitions
        execute("insert into table1 (a,b) values (1,1), (2,2), (3,3)");
        execute("refresh table table1");
        Metadata updatedMetadata = cluster().clusterService().state().metadata();
        List<String> indexUUIDs = new ArrayList<>();
        for (ObjectCursor<String> cursor: updatedMetadata.indices().keys()) {
            indexUUIDs.add(updatedMetadata.index(cursor.value).getIndexUUID());
        }

        // Try to insert into same partitions, when index is created it should not be re-created
        // Only 1 new index should be created here, for a partition 4.
        // Existing rows shouldn't be lost.
        ClusterState currentState = cluster().clusterService().state();
        updatedMetadata = currentState.metadata();
        List<String> newUUIDs = new ArrayList<>();
        for (ObjectCursor<String> cursor: updatedMetadata.indices().keys()) {
            newUUIDs.add(updatedMetadata.index(cursor.value).getIndexUUID());
        }
        assertThat(newUUIDs).containsAll(indexUUIDs); // old indices are still there, they were not overwritten

        execute("insert into table1 (a,b) values (1,1), (2,2), (3,3), (4,4)");
        execute("refresh table table1");
        execute("select a, b from table1 order by a, b");
        assertThat(response)
            .hasRows(
                "1| 1",
                "1| 1",
                "2| 2",
                "2| 2",
                "3| 3",
                "3| 3",
                "4| 4"
            );
    }

    @Test
    public void testEmpty() throws Exception {
        assertThatThrownBy(() -> CreatePartitionsRequest.of(List.of()))
            .hasMessage("Must create at least one partition");
    }
}

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
package org.elasticsearch.test.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.elasticsearch.discovery.DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.SettingsBasedSeedHostsProvider;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.MockHttpTransport;
import org.elasticsearch.test.NodeConfigurationSource;
import org.elasticsearch.test.TestCluster;
import org.junit.Test;

import io.crate.common.io.IOUtils;

/**
 * Basic test that ensure that the internal cluster reproduces the same
 * configuration given the same seed / input.
 */
@LuceneTestCase.SuppressFileSystems("ExtrasFS") // doesn't work with potential multi data path from test cluster yet
public class InternalTestClusterTests extends ESTestCase {

    private static Collection<Class<? extends Plugin>> mockPlugins() {
        return Arrays.asList(MockHttpTransport.TestPlugin.class);
    }

    @Test
    public void testInitializiationIsConsistent() {
        long clusterSeed = randomLong();
        boolean masterNodes = randomBoolean();
        int minNumDataNodes = randomIntBetween(0, 9);
        int maxNumDataNodes = randomIntBetween(minNumDataNodes, 10);
        String clusterName = randomRealisticUnicodeOfCodepointLengthBetween(1, 10);
        NodeConfigurationSource nodeConfigurationSource = NodeConfigurationSource.EMPTY;
        int numClientNodes = randomIntBetween(0, 10);
        String nodePrefix = randomRealisticUnicodeOfCodepointLengthBetween(1, 10);

        Path baseDir = createTempDir();
        TestCluster cluster0 = new TestCluster(clusterSeed, baseDir, masterNodes,
                                                               randomBoolean(), minNumDataNodes, maxNumDataNodes, clusterName, nodeConfigurationSource, numClientNodes,
                                                               nodePrefix, Collections.emptyList());
        TestCluster cluster1 = new TestCluster(clusterSeed, baseDir, masterNodes,
                                                               randomBoolean(), minNumDataNodes, maxNumDataNodes, clusterName, nodeConfigurationSource, numClientNodes,
                                                               nodePrefix, Collections.emptyList());
        assertClusters(cluster0, cluster1, true);
    }

    /**
     * a set of settings that are expected to have different values between clusters, even they have been initialized with the same
     * base settings.
     */
    static final Set<String> clusterUniqueSettings = new HashSet<>();

    static {
        clusterUniqueSettings.add(Environment.PATH_HOME_SETTING.getKey());
        clusterUniqueSettings.add(Environment.PATH_DATA_SETTING.getKey());
        clusterUniqueSettings.add(Environment.PATH_REPO_SETTING.getKey());
        clusterUniqueSettings.add(Environment.PATH_SHARED_DATA_SETTING.getKey());
        clusterUniqueSettings.add(Environment.PATH_LOGS_SETTING.getKey());
    }

    public static void assertClusters(TestCluster cluster0, TestCluster cluster1, boolean checkClusterUniqueSettings) {
        Settings defaultSettings0 = cluster0.getDefaultSettings();
        Settings defaultSettings1 = cluster1.getDefaultSettings();
        assertSettings(defaultSettings0, defaultSettings1, checkClusterUniqueSettings);
        assertThat(cluster0.numDataNodes()).isEqualTo(cluster1.numDataNodes());
    }

    public static void assertSettings(Settings left, Settings right, boolean checkClusterUniqueSettings) {
        Set<String> keys0 = left.keySet();
        Set<String> keys1 = right.keySet();
        assertThat(keys0)
            .as("--> left:\n" + left.toDelimitedString('\n') +  "\n-->right:\n" + right.toDelimitedString('\n'))
            .hasSize(keys1.size());
        for (String key : keys0) {
            if (clusterUniqueSettings.contains(key) && checkClusterUniqueSettings == false) {
                continue;
            }
            assertThat(keys1.contains(key)).as("key [" + key + "] is missing in " + keys1).isTrue();
            assertThat(left.get(key)).as(key).isEqualTo(right.get(key));
        }
    }

    @Test
    public void testBeforeTest() throws Exception {
        final boolean autoManageMinMasterNodes = randomBoolean();
        long clusterSeed = randomLong();
        final boolean masterNodes;
        final int minNumDataNodes;
        final int maxNumDataNodes;
        final int bootstrapMasterNodeIndex;
        if (autoManageMinMasterNodes) {
            masterNodes = randomBoolean();
            minNumDataNodes = randomIntBetween(0, 3);
            maxNumDataNodes = randomIntBetween(minNumDataNodes, 4);
            bootstrapMasterNodeIndex = -1;
        } else {
            // if we manage min master nodes, we need to lock down the number of nodes
            minNumDataNodes = randomIntBetween(0, 4);
            maxNumDataNodes = minNumDataNodes;
            masterNodes = false;
            bootstrapMasterNodeIndex = maxNumDataNodes == 0 ? -1 : randomIntBetween(0, maxNumDataNodes - 1);
        }
        final int numClientNodes = randomIntBetween(0, 2);
        NodeConfigurationSource nodeConfigurationSource = new NodeConfigurationSource() {
            @Override
            public Settings nodeSettings(int nodeOrdinal) {
                final Settings.Builder settings = Settings.builder()
                    .put(DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "file")
                    .putList(SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING.getKey());
                if (autoManageMinMasterNodes == false) {
                    assert minNumDataNodes == maxNumDataNodes;
                    assert masterNodes == false;
                }
                return settings.build();
            }

            @Override
            public Path nodeConfigPath(int nodeOrdinal) {
                return null;
            }

            @Override
            public Settings transportClientSettings() {
                return Settings.EMPTY;
            }
        };

        String nodePrefix = "foobar";
        TestCluster cluster0 = new TestCluster(
            clusterSeed,
            createTempDir(),
            masterNodes,
            autoManageMinMasterNodes,
            minNumDataNodes,
            maxNumDataNodes,
            "clustername",
            nodeConfigurationSource,
            numClientNodes,
            nodePrefix,
            mockPlugins()
        );
        cluster0.setBootstrapMasterNodeIndex(bootstrapMasterNodeIndex);

        TestCluster cluster1 = new TestCluster(
            clusterSeed,
            createTempDir(),
            masterNodes,
            autoManageMinMasterNodes,
            minNumDataNodes,
            maxNumDataNodes,
            "clustername",
            nodeConfigurationSource,
            numClientNodes,
            nodePrefix,
            mockPlugins()
        );
        cluster1.setBootstrapMasterNodeIndex(bootstrapMasterNodeIndex);

        assertClusters(cluster0, cluster1, false);
        long seed = randomLong();
        try {
            {
                Random random = new Random(seed);
                cluster0.beforeTest(random);
            }
            {
                Random random = new Random(seed);
                cluster1.beforeTest(random);
            }
            assertThat(cluster1.getNodeNames()).isEqualTo(cluster0.getNodeNames());
            cluster0.afterTest();
            cluster1.afterTest();
        } finally {
            IOUtils.close(cluster0, cluster1);
        }
    }

    @Test
    public void testDataFolderAssignmentAndCleaning() throws IOException, InterruptedException {
        long clusterSeed = randomLong();
        boolean masterNodes = randomBoolean();
        // we need one stable node
        final int minNumDataNodes = 2;
        final int maxNumDataNodes = 2;
        final int numClientNodes = randomIntBetween(0, 2);
        final String clusterName1 = "shared1";
        NodeConfigurationSource nodeConfigurationSource = new NodeConfigurationSource() {
            @Override
            public Settings nodeSettings(int nodeOrdinal) {
                return Settings.builder()
                    .putList(DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "file")
                    .putList(SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING.getKey())
                    .build();
            }

            @Override
            public Path nodeConfigPath(int nodeOrdinal) {
                return null;
            }

            @Override
            public Settings transportClientSettings() {
                return Settings.EMPTY;
            }
        };
        String nodePrefix = "test";
        Path baseDir = createTempDir();
        TestCluster cluster = new TestCluster(
            clusterSeed,
            baseDir,
            masterNodes,
            true,
            minNumDataNodes,
            maxNumDataNodes,
            clusterName1,
            nodeConfigurationSource,
            numClientNodes,
            nodePrefix,
            mockPlugins()
        );
        try {
            cluster.beforeTest(random());
            final int originalMasterCount = cluster.numMasterNodes();
            final Map<String,Path[]> shardNodePaths = new HashMap<>();
            for (String name: cluster.getNodeNames()) {
                shardNodePaths.put(name, getNodePaths(cluster, name));
            }
            String poorNode = randomValueOtherThanMany(n -> originalMasterCount == 1 && n.equals(cluster.getMasterName()),
                                                       () -> randomFrom(cluster.getNodeNames()));
            Path dataPath = getNodePaths(cluster, poorNode)[0];
            final Settings poorNodeDataPathSettings = cluster.dataPathSettings(poorNode);
            final Path testMarker = dataPath.resolve("testMarker");
            Files.createDirectories(testMarker);
            cluster.stopRandomNode(TestCluster.nameFilter(poorNode));
            assertThat(testMarker).exists(); // stopping a node half way shouldn't clean data

            final String stableNode = randomFrom(cluster.getNodeNames());
            final Path stableDataPath = getNodePaths(cluster, stableNode)[0];
            final Path stableTestMarker = stableDataPath.resolve("stableTestMarker");
            assertThat(stableDataPath).isNotEqualTo(dataPath);
            Files.createDirectories(stableTestMarker);

            final String newNode1 =  cluster.startNode();
            assertThat(getNodePaths(cluster, newNode1)[0]).isNotEqualTo(dataPath);
            assertThat(testMarker).exists(); // starting a node should re-use data folders and not clean it
            final String newNode2 =  cluster.startNode();
            final Path newDataPath = getNodePaths(cluster, newNode2)[0];
            final Path newTestMarker = newDataPath.resolve("newTestMarker");
            assertThat(newDataPath).isNotEqualTo(dataPath);
            Files.createDirectories(newTestMarker);
            final String newNode3 =  cluster.startNode(poorNodeDataPathSettings);
            assertThat(getNodePaths(cluster, newNode3)[0]).isEqualTo(dataPath);
            cluster.beforeTest(random());
            assertThat(newTestMarker).doesNotExist(); // the cluster should be reset for a new test, cleaning up the extra path we made
            assertThat(testMarker).doesNotExist(); // a new unknown node used this path, it should be cleaned
            assertThat(stableTestMarker).exists(); // but leaving the structure of existing, reused nodes
            for (String name: cluster.getNodeNames()) {
                assertThat(getNodePaths(cluster, name))
                    .as("data paths for " + name + " changed")
                    .isEqualTo(shardNodePaths.get(name));
            }

            cluster.beforeTest(random());
            assertThat(stableTestMarker).exists(); // but leaving the structure of existing, reused nodes
            for (String name: cluster.getNodeNames()) {
                assertThat(getNodePaths(cluster, name))
                    .as("data paths for " + name + " changed")
                    .isEqualTo(shardNodePaths.get(name));
            }
        } finally {
            cluster.close();
        }
    }

    private Path[] getNodePaths(TestCluster cluster, String name) {
        final NodeEnvironment nodeEnvironment = cluster.getInstance(NodeEnvironment.class, name);
        if (nodeEnvironment.hasNodeFile()) {
            return nodeEnvironment.nodeDataPaths();
        } else {
            return new Path[0];
        }
    }

    @Test
    public void testDifferentRolesMaintainPathOnRestart() throws Exception {
        final Path baseDir = createTempDir();
        final int numNodes = 5;

        TestCluster cluster = new TestCluster(randomLong(), baseDir, false,
                                                              false, 0, 0, "test", new NodeConfigurationSource() {

            @Override
            public Settings nodeSettings(int nodeOrdinal) {
                return Settings.builder()
                    .put(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey(), 0)
                    .putList(DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "file")
                    .putList(SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING.getKey())
                    .build();
            }

            @Override
            public Path nodeConfigPath(int nodeOrdinal) {
                return null;
            }

            @Override
            public Settings transportClientSettings() {
                return Settings.EMPTY;
            }
        }, 0, "", mockPlugins());
        cluster.beforeTest(random());
        List<DiscoveryNodeRole> roles = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            final DiscoveryNodeRole role = i == numNodes - 1 && roles.contains(DiscoveryNodeRole.MASTER_ROLE) == false ?
                    DiscoveryNodeRole.MASTER_ROLE : // last node and still no master
                randomFrom(DiscoveryNodeRole.MASTER_ROLE, DiscoveryNodeRole.DATA_ROLE);
            roles.add(role);
        }

        cluster.setBootstrapMasterNodeIndex(
                randomIntBetween(0, (int) roles.stream().filter(role -> role.equals(DiscoveryNodeRole.MASTER_ROLE)).count() - 1));

        try {
            Map<DiscoveryNodeRole, Set<String>> pathsPerRole = new HashMap<>();
            for (int i = 0; i < numNodes; i++) {
                final DiscoveryNodeRole role = roles.get(i);
                final String node;
                if (role == DiscoveryNodeRole.MASTER_ROLE) {
                    node = cluster.startMasterOnlyNode();
                } else if (role == DiscoveryNodeRole.DATA_ROLE) {
                    node = cluster.startDataOnlyNode();
                } else {
                    throw new IllegalStateException("get your story straight");
                }
                Set<String> rolePaths = pathsPerRole.computeIfAbsent(role, k -> new HashSet<>());
                for (Path path : getNodePaths(cluster, node)) {
                    assertThat(rolePaths.add(path.toString())).isTrue();
                }
            }
            cluster.validateClusterFormed();
            cluster.fullRestart();

            Map<DiscoveryNodeRole, Set<String>> result = new HashMap<>();
            for (String name : cluster.getNodeNames()) {
                DiscoveryNode node = cluster.getInstance(ClusterService.class, name).localNode();
                List<String> paths = Arrays.stream(getNodePaths(cluster, name)).map(Path::toString).collect(Collectors.toList());
                if (node.isMasterEligibleNode()) {
                    result.computeIfAbsent(DiscoveryNodeRole.MASTER_ROLE, k -> new HashSet<>()).addAll(paths);
                } else {
                    result.computeIfAbsent(DiscoveryNodeRole.DATA_ROLE, k -> new HashSet<>()).addAll(paths);
                }
            }

            assertThat(result).hasSize(pathsPerRole.size());
            for (DiscoveryNodeRole role : result.keySet()) {
                assertThat(result.get(role))
                    .as("path are not the same for " + role)
                    .isEqualTo(pathsPerRole.get(role));
            }
        } finally {
            cluster.close();
        }
    }

    @Test
    public void testTwoNodeCluster() throws Exception {
        NodeConfigurationSource nodeConfigurationSource = new NodeConfigurationSource() {
            @Override
            public Settings nodeSettings(int nodeOrdinal) {
                return Settings.builder()
                    .putList(DISCOVERY_SEED_PROVIDERS_SETTING.getKey(), "file")
                    .putList(SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING.getKey())
                    .build();
            }

            @Override
            public Path nodeConfigPath(int nodeOrdinal) {
                return null;
            }

            @Override
            public Settings transportClientSettings() {
                return Settings.EMPTY;
            }
        };
        String nodePrefix = "test";
        Path baseDir = createTempDir();
        List<Class<? extends Plugin>> plugins = new ArrayList<>(mockPlugins());
        plugins.add(NodeAttrCheckPlugin.class);
        TestCluster cluster = new TestCluster(randomLong(), baseDir, false, true, 2, 2,
                                                              "test", nodeConfigurationSource, 0, nodePrefix,
                                                              plugins);
        try {
            cluster.beforeTest(random());
            switch (randomInt(2)) {
                case 0:
                    cluster.stopRandomDataNode();
                    cluster.startNode();
                    break;
                case 1:
                    cluster.rollingRestart(TestCluster.EMPTY_CALLBACK);
                    break;
                case 2:
                    cluster.fullRestart();
                    break;
            }
        } finally {
            cluster.close();
        }
    }

    /**
     * Plugin that adds a simple node attribute as setting and checks if that node attribute is not already defined.
     * Allows to check that the full-cluster restart logic does not copy over plugin-derived settings.
     */
    public static class NodeAttrCheckPlugin extends Plugin {

        private final Settings settings;

        public NodeAttrCheckPlugin(Settings settings) {
            this.settings = settings;
        }

        @Override
        public Settings additionalSettings() {
            if (settings.get("node.attr.dummy") != null) {
                fail("dummy setting already exists");
            }
            return Settings.builder().put("node.attr.dummy", true).build();
        }
    }
}

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

package org.elasticsearch.test;

import static io.crate.common.unit.TimeValue.timeValueSeconds;
import static io.crate.lucene.CrateLuceneTestCase.rarely;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.elasticsearch.cluster.coordination.ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING;
import static org.elasticsearch.discovery.DiscoveryModule.DISCOVERY_TYPE_SETTING;
import static org.elasticsearch.discovery.DiscoveryModule.ZEN2_DISCOVERY_TYPE;
import static org.elasticsearch.test.ESTestCase.assertBusy;
import static org.elasticsearch.test.ESTestCase.randomFrom;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.elasticsearch.action.admin.cluster.configuration.AddVotingConfigExclusionsRequest;
import org.elasticsearch.action.admin.cluster.configuration.ClearVotingConfigExclusionsRequest;
import org.elasticsearch.action.admin.cluster.configuration.TransportAddVotingConfigExclusions;
import org.elasticsearch.action.admin.cluster.configuration.TransportClearVotingConfigExclusions;
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest;
import org.elasticsearch.action.admin.cluster.repositories.delete.TransportDeleteRepository;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.ClusterBootstrapService;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.OperationRouting;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdSettings;
import org.elasticsearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.component.LifecycleListener;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.discovery.FileBasedSeedHostsProvider;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.ShardLockObtainFailedException;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.CommitStats;
import org.elasticsearch.index.engine.DocIdAndSeqNo;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineTestCase;
import org.elasticsearch.index.engine.InternalEngine;
import org.elasticsearch.index.seqno.SeqNoStats;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.node.MockNode;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.test.disruption.ServiceDisruptionScheme;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.TransportSettings;
import org.jetbrains.annotations.Nullable;

import com.carrotsearch.hppc.ObjectLongMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.SeedUtils;
import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;

import io.crate.common.concurrent.CompletableFutures;
import io.crate.common.io.IOUtils;
import io.crate.common.unit.TimeValue;
import io.crate.execution.ddl.tables.DropTableRequest;
import io.crate.execution.ddl.tables.TransportDropTable;
import io.crate.execution.engine.collect.sources.InformationSchemaIterables;
import io.crate.execution.jobs.TasksService;
import io.crate.metadata.RelationInfo.RelationType;
import io.crate.metadata.RelationName;
import io.crate.metadata.SystemTable;
import io.crate.protocols.postgres.PostgresNetty;
import io.crate.session.Sessions;
import io.crate.testing.SQLTransportExecutor;

/**
 * TestCluster manages a set of JVM private nodes and allows convenient access to them.
 * The cluster supports randomized configuration such that nodes started in the cluster will
 * automatically load asserting services tracking resources like file handles or open searchers.
 * <p>
 * The Cluster is bound to a test lifecycle where tests must call {@link #beforeTest(java.util.Random)} and
 * {@link #afterTest()} to initialize and reset the cluster in order to be more reproducible. The term "more" relates
 * to the async nature of CrateDB in combination with randomized testing. Once Threads and asynchronous calls
 * are involved reproducibility is very limited. This class should only be used through {@link IntegTestCase}.
 * </p>
 */
public final class TestCluster implements Closeable {

    protected final Logger logger = LogManager.getLogger(getClass());
    private final long seed;

    protected Random random;

    private static final Predicate<NodeAndClient> DATA_NODE_PREDICATE =
        nodeAndClient -> DiscoveryNode.isDataNode(nodeAndClient.node.settings());

    private static final Predicate<NodeAndClient> NO_DATA_NO_MASTER_PREDICATE = nodeAndClient ->
        DiscoveryNode.isMasterEligibleNode(nodeAndClient.node.settings()) == false
            && DiscoveryNode.isDataNode(nodeAndClient.node.settings()) == false;

    private static final Predicate<NodeAndClient> MASTER_NODE_PREDICATE =
        nodeAndClient -> DiscoveryNode.isMasterEligibleNode(nodeAndClient.node.settings());

    public static final int DEFAULT_LOW_NUM_MASTER_NODES = 1;
    public static final int DEFAULT_HIGH_NUM_MASTER_NODES = 3;

    static final int DEFAULT_MIN_NUM_DATA_NODES = 1;
    static final int DEFAULT_MAX_NUM_DATA_NODES = LuceneTestCase.TEST_NIGHTLY ? 6 : 3;

    static final int DEFAULT_NUM_CLIENT_NODES = -1;
    static final int DEFAULT_MIN_NUM_CLIENT_NODES = 0;
    static final int DEFAULT_MAX_NUM_CLIENT_NODES = 1;

    /* Sorted map to make traverse order reproducible.
     * The map of nodes is never mutated so individual reads are safe without synchronization.
     * Updates are intended to follow a copy-on-write approach. */
    private volatile NavigableMap<String, NodeAndClient> nodes = Collections.emptyNavigableMap();

    private final Set<Path> dataDirToClean = new HashSet<>();

    private final String clusterName;

    private final AtomicBoolean open = new AtomicBoolean(true);

    private final Settings defaultSettings;

    private final AtomicInteger nextNodeId = new AtomicInteger(0);

    /* Each shared node has a node seed that is used to start up the node and get default settings
     * this is important if a node is randomly shut down in a test since the next test relies on a
     * fully shared cluster to be more reproducible */
    private final long[] sharedNodesSeeds;


    // if set to 0, data nodes will also assume the master role
    private final int numSharedDedicatedMasterNodes;

    private final int numSharedDataNodes;

    private final int numSharedCoordOnlyNodes;

    private final NodeConfigurationSource nodeConfigurationSource;

    private final ExecutorService executor;

    private final boolean autoManageMasterNodes;

    private final Collection<Class<? extends Plugin>> mockPlugins;

    private final int numDataPaths;

    /**
     * All nodes started by the cluster will have their name set to nodePrefix followed by a positive number
     */
    private final String nodePrefix;
    private final Path baseDir;

    private ServiceDisruptionScheme activeDisruptionScheme;

    private int bootstrapMasterNodeIndex = -1;

    public TestCluster(
            final long clusterSeed,
            final Path baseDir,
            final boolean randomlyAddDedicatedMasters,
            final boolean autoManageMasterNodes,
            final int minNumDataNodes,
            final int maxNumDataNodes,
            final String clusterName,
            final NodeConfigurationSource nodeConfigurationSource,
            final int numClientNodes,
            final String nodePrefix,
            final Collection<Class<? extends Plugin>> mockPlugins) {
        this.seed = clusterSeed;
        this.autoManageMasterNodes = autoManageMasterNodes;
        this.baseDir = baseDir;
        this.clusterName = clusterName;
        if (minNumDataNodes < 0 || maxNumDataNodes < 0) {
            throw new IllegalArgumentException("minimum and maximum number of data nodes must be >= 0");
        }

        if (maxNumDataNodes < minNumDataNodes) {
            throw new IllegalArgumentException("maximum number of data nodes must be >= minimum number of  data nodes");
        }

        Random random = new Random(clusterSeed);

        boolean useDedicatedMasterNodes = randomlyAddDedicatedMasters && random.nextBoolean();

        this.numSharedDataNodes = RandomNumbers.randomIntBetween(random, minNumDataNodes, maxNumDataNodes);
        assert this.numSharedDataNodes >= 0;

        if (numSharedDataNodes == 0) {
            this.numSharedCoordOnlyNodes = 0;
            this.numSharedDedicatedMasterNodes = 0;
        } else {
            if (useDedicatedMasterNodes) {
                if (random.nextBoolean()) {
                    // use a dedicated master, but only low number to reduce overhead to tests
                    this.numSharedDedicatedMasterNodes = DEFAULT_LOW_NUM_MASTER_NODES;
                } else {
                    this.numSharedDedicatedMasterNodes = DEFAULT_HIGH_NUM_MASTER_NODES;
                }
            } else {
                this.numSharedDedicatedMasterNodes = 0;
            }
            if (numClientNodes < 0) {
                this.numSharedCoordOnlyNodes =  RandomNumbers.randomIntBetween(random,
                        DEFAULT_MIN_NUM_CLIENT_NODES, DEFAULT_MAX_NUM_CLIENT_NODES);
            } else {
                this.numSharedCoordOnlyNodes = numClientNodes;
            }
        }
        assert this.numSharedCoordOnlyNodes >= 0;

        this.nodePrefix = nodePrefix;

        assert nodePrefix != null;

        this.mockPlugins = mockPlugins;

        sharedNodesSeeds = new long[numSharedDedicatedMasterNodes + numSharedDataNodes + numSharedCoordOnlyNodes];
        for (int i = 0; i < sharedNodesSeeds.length; i++) {
            sharedNodesSeeds[i] = random.nextLong();
        }

        logger.info("Setup TestCluster [{}] with seed [{}] using [{}] dedicated masters, " +
                "[{}] (data) nodes and [{}] coord only nodes (master nodes are [{}])",
            clusterName, SeedUtils.formatSeed(clusterSeed),
            numSharedDedicatedMasterNodes, numSharedDataNodes, numSharedCoordOnlyNodes,
            autoManageMasterNodes ? "auto-managed" : "manual");
        this.nodeConfigurationSource = nodeConfigurationSource;
        numDataPaths = random.nextInt(5) == 0 ? 2 + random.nextInt(3) : 1;
        Builder builder = Settings.builder();
        builder.put(Environment.PATH_HOME_SETTING.getKey(), baseDir);
        builder.put(Environment.PATH_REPO_SETTING.getKey(), baseDir.resolve("repos"));
        builder.put(TransportSettings.PORT.getKey(), 0);
        builder.put("http.port", 0);
        if (Strings.hasLength(System.getProperty("tests.es.logger.level"))) {
            builder.put("logger.level", System.getProperty("tests.es.logger.level"));
        }
        if (Strings.hasLength(System.getProperty("es.logger.prefix"))) {
            builder.put("logger.prefix", System.getProperty("es.logger.prefix"));
        }

        builder.put(HierarchyCircuitBreakerService.TOTAL_CIRCUIT_BREAKER_LIMIT_SETTING.getKey(), "100%");

        // Default the watermarks to absurdly low to prevent the tests
        // from failing on nodes without enough disk space
        builder.put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "1b");
        builder.put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "1b");
        builder.put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "1b");
        if (LuceneTestCase.TEST_NIGHTLY) {
            builder.put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_INCOMING_RECOVERIES_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 5, 10));
            builder.put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_OUTGOING_RECOVERIES_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 5, 10));
        } else if (random.nextInt(100) <= 90) {
            builder.put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_INCOMING_RECOVERIES_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 2, 5));
            builder.put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_NODE_CONCURRENT_OUTGOING_RECOVERIES_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 2, 5));
        }
        // always reduce this - it can make tests really slow
        builder.put(RecoverySettings.INDICES_RECOVERY_RETRY_DELAY_STATE_SYNC_SETTING.getKey(), TimeValue.timeValueMillis(
                RandomNumbers.randomIntBetween(random, 20, 50)));
        builder.put(RecoverySettings.INDICES_RECOVERY_MAX_CONCURRENT_FILE_CHUNKS_SETTING.getKey(),
                    RandomNumbers.randomIntBetween(random, 1, 5));
        defaultSettings = builder.build();
        executor = EsExecutors.newScaling(
            "internal_test_cluster_executor",
            0,
            Integer.MAX_VALUE,
            0,
            TimeUnit.SECONDS,
            EsExecutors.daemonThreadFactory("test_" + clusterName));
    }

    public long seed() {
        return seed;
    }

    /**
     * This method should be executed before each test to reset the cluster to its initial state.
     */
    public void beforeTest(Random random) throws IOException, InterruptedException {
        this.random = new Random(random.nextLong());
        reset(true);
    }

    /**
     * Wipes any data that a test can leave behind: indices, templates (except exclude templates) and repositories
     */
    public void wipe() {
        wipeAllTables();
        wipeRepositories();
    }

    public void wipeAllTables() {
        if (size() > 0) {
            InformationSchemaIterables infoSchema = getCurrentMasterNodeInstance(InformationSchemaIterables.class);
            List<CompletableFuture<AcknowledgedResponse>> futures = new ArrayList<>();
            List<RelationName> relationNames = new ArrayList<>();
            for (var relation : infoSchema.relations()) {
                if (relation.relationType() == RelationType.BASE_TABLE && relation instanceof SystemTable<?> == false) {
                    relationNames.add(relation.ident());
                    futures.add(client().execute(TransportDropTable.ACTION, new DropTableRequest(relation.ident())));
                }
            }
            CompletableFuture<List<AcknowledgedResponse>> allResponses = CompletableFutures.allAsList(futures);
            try {
                List<AcknowledgedResponse> responses = allResponses.get(5, TimeUnit.SECONDS);
                responses.forEach(r -> assertAcked(r));
            } catch (Exception ignore) {
            }
            for (RelationName name : relationNames) {
                assertThat(clusterService().state().metadata().contains(name))
                    .as("wipeAllTables must remove " + name)
                    .isFalse();
            }
        }
    }


    /**
     * Deletes repositories, supports wildcard notation.
     */
    public void wipeRepositories(String... repositories) {
        if (size() > 0) {
            // if nothing is provided, delete all
            if (repositories.length == 0) {
                repositories = new String[]{"*"};
            }
            for (String repository : repositories) {
                try {
                    client().execute(TransportDeleteRepository.ACTION, new DeleteRepositoryRequest().name(repository)).get();
                } catch (InterruptedException | ExecutionException | RepositoryMissingException ex) {
                    // ignore
                }
            }
        }
    }

    /**
     * Ensures that any breaker statistics are reset to 0.
     *
     * The implementation is specific to the test cluster, because the act of
     * checking some breaker stats can increase them.
     */
    public void ensureEstimatedStats() {
        // Checks that the breakers have been reset without incurring a
        // network request, because a network request can increment one
        // of the breakers
        for (NodeAndClient nodeAndClient : nodes.values()) {
            final String name = nodeAndClient.name;
            final CircuitBreakerService breakerService = getInstanceFromNode(CircuitBreakerService.class, nodeAndClient.node);
            // Anything that uses transport or HTTP can increase the
            // request breaker (because they use bigarrays), because of
            // that the breaker can sometimes be incremented from ping
            // requests from other clusters because Jenkins is running
            // multiple ES testing jobs in parallel on the same machine.
            // To combat this we check whether the breaker has reached 0
            // in an assertBusy loop, so it will try for 10 seconds and
            // fail if it never reached 0
            try {
                assertBusy(() -> {
                    CircuitBreaker reqBreaker = breakerService.getBreaker(CircuitBreaker.REQUEST);
                    assertThat(reqBreaker.getUsed())
                        .as("Request breaker not reset to 0 on node: " + name)
                        .isEqualTo(0L);
                });
            } catch (Exception e) {
                throw new AssertionError("Exception during check for request breaker reset to 0", e);
            }

            // RamAccounting release operations can run asynchronous after clients already received results.
            try {
                assertBusy(() -> {
                    CircuitBreaker crateQueryBreaker = breakerService.getBreaker("query");
                    if (crateQueryBreaker != null) {
                        assertThat(crateQueryBreaker.getUsed())
                            .as("Query breaker not reset to 0 on node: " + name)
                            .isEqualTo(0L);
                    }
                });
            } catch (Exception e) {
                throw new AssertionError("Exception during check for query breaker reset to 0", e);
            }
        }
    }


    /**
     * Sets {@link #bootstrapMasterNodeIndex} to the given value, see {@link #bootstrapMasterNodeWithSpecifiedIndex(List)}
     * for the description of how this field is used.
     * It's only possible to change {@link #bootstrapMasterNodeIndex} value if autoManageMasterNodes is false.
     */
    public void setBootstrapMasterNodeIndex(int bootstrapMasterNodeIndex) {
        assert autoManageMasterNodes == false || bootstrapMasterNodeIndex == -1
            : "bootstrapMasterNodeIndex should be -1 if autoManageMasterNodes is true, but was " + bootstrapMasterNodeIndex;
        this.bootstrapMasterNodeIndex = bootstrapMasterNodeIndex;
    }

    /**
     * Returns the cluster name
     */
    public String getClusterName() {
        return clusterName;
    }

    public String[] getNodeNames() {
        return nodes.keySet().toArray(Strings.EMPTY_ARRAY);
    }

    private Settings getSettings(int nodeOrdinal, long nodeSeed, Settings others) {
        Builder builder = Settings.builder().put(defaultSettings)
            .put(getRandomNodeSettings(nodeSeed));
        Settings settings = nodeConfigurationSource.nodeSettings(nodeOrdinal);
        if (settings != null) {
            if (settings.get(ClusterName.CLUSTER_NAME_SETTING.getKey()) != null) {
                throw new IllegalStateException("Tests must not set a '" + ClusterName.CLUSTER_NAME_SETTING.getKey()
                        + "' as a node setting set '" + ClusterName.CLUSTER_NAME_SETTING.getKey() + "': ["
                        + settings.get(ClusterName.CLUSTER_NAME_SETTING.getKey()) + "]");
            }
            builder.put(settings);
        }
        if (others != null) {
            builder.put(others);
        }
        builder.put(ClusterName.CLUSTER_NAME_SETTING.getKey(), clusterName);
        return builder.build();
    }

    public Collection<Class<? extends Plugin>> getPlugins() {
        Set<Class<? extends Plugin>> plugins = new HashSet<>(nodeConfigurationSource.nodePlugins());
        plugins.addAll(mockPlugins);
        return plugins;
    }

    private static Settings getRandomNodeSettings(long seed) {
        Random random = new Random(seed);
        Builder builder = Settings.builder();
        builder.put(TransportSettings.TRANSPORT_COMPRESS.getKey(), rarely(random));
        if (random.nextBoolean()) {
            builder.put("cache.recycler.page.type", RandomPicks.randomFrom(random, PageCacheRecycler.Type.values()));
        }

        builder.put(EsExecutors.PROCESSORS_SETTING.getKey(), 1 + random.nextInt(3));

        // randomize tcp settings
        if (random.nextBoolean()) {
            builder.put(TransportSettings.CONNECTIONS_PER_NODE_RECOVERY.getKey(), random.nextInt(2) + 1);
            builder.put(TransportSettings.CONNECTIONS_PER_NODE_BULK.getKey(), random.nextInt(3) + 1);
            builder.put(TransportSettings.CONNECTIONS_PER_NODE_REG.getKey(), random.nextInt(6) + 1);
        }


        if (random.nextBoolean()) {
            if (random.nextInt(10) == 0) { // do something crazy slow here
                builder.put(RecoverySettings.INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey(),
                        new ByteSizeValue(RandomNumbers.randomIntBetween(random, 1, 10), ByteSizeUnit.MB));
            } else {
                builder.put(RecoverySettings.INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey(),
                        new ByteSizeValue(RandomNumbers.randomIntBetween(random, 10, 200), ByteSizeUnit.MB));
            }
        }

        if (random.nextBoolean()) {
            builder.put(TransportSettings.PING_SCHEDULE.getKey(), RandomNumbers.randomIntBetween(random, 100, 2000) + "ms");
        }

        if (random.nextBoolean()) {
            int initialMillisBound = RandomNumbers.randomIntBetween(random,10, 100);
            builder.put(TransportReplicationAction.REPLICATION_INITIAL_RETRY_BACKOFF_BOUND.getKey(), TimeValue.timeValueMillis(initialMillisBound));
            int retryTimeoutSeconds = RandomNumbers.randomIntBetween(random, 0, 60);
            builder.put(TransportReplicationAction.REPLICATION_RETRY_TIMEOUT.getKey(), timeValueSeconds(retryTimeoutSeconds));
        }

        return builder.build();
    }

    public static String clusterName(String prefix, long clusterSeed) {
        StringBuilder builder = new StringBuilder(prefix);
        builder.append("-TEST_WORKER_VM=[").append(ESTestCase.TEST_WORKER_VM_ID).append(']');
        builder.append("-CLUSTER_SEED=[").append(clusterSeed).append(']');
        // if multiple maven task run on a single host we better have an identifier that doesn't rely on input params
        builder.append("-HASH=[").append(SeedUtils.formatSeed(System.nanoTime())).append(']');
        return builder.toString();
    }

    private void ensureOpen() {
        if (!open.get()) {
            throw new RuntimeException("Cluster is already closed");
        }
    }

    private NodeAndClient getOrBuildRandomNode() {
        assert Thread.holdsLock(this);
        final NodeAndClient randomNodeAndClient = getRandomNodeAndClient();
        if (randomNodeAndClient != null) {
            return randomNodeAndClient;
        }
        final Runnable onTransportServiceStarted = () -> {}; // do not create unicast host file for this one node.

        final int nodeId = nextNodeId.getAndIncrement();
        final Settings settings = getNodeSettings(nodeId, random.nextLong(), Settings.EMPTY);
        final Settings nodeSettings = Settings.builder()
                .putList(INITIAL_MASTER_NODES_SETTING.getKey(), Node.NODE_NAME_SETTING.get(settings))
                .put(settings)
                .build();
        final NodeAndClient buildNode = buildNode(nodeId, nodeSettings, false, onTransportServiceStarted);
        assert nodes.isEmpty();
        buildNode.startNode();
        publishNode(buildNode);
        return buildNode;
    }

    private NodeAndClient getRandomNodeAndClient() {
        return getRandomNodeAndClient(nc -> true);
    }

    private synchronized NodeAndClient getRandomNodeAndClient(Predicate<NodeAndClient> predicate) {
        ensureOpen();
        List<NodeAndClient> values = nodes.values().stream().filter(predicate).toList();
        if (values.isEmpty() == false) {
            return randomFrom(random, values);
        }
        return null;
    }

    /**
     * Ensures that at least <code>n</code> data nodes are present in the cluster.
     * if more nodes than <code>n</code> are present this method will not
     * stop any of the running nodes.
     */
    public synchronized void ensureAtLeastNumDataNodes(int n) {
        int size = numDataNodes();
        if (size < n) {
            logger.info("increasing cluster size from {} to {}", size, n);
            if (numSharedDedicatedMasterNodes > 0) {
                startDataOnlyNodes(n - size);
            } else {
                startNodes(n - size);
            }
            validateClusterFormed();
        }
    }

    /**
     * Ensures that at most <code>n</code> are up and running.
     * If less nodes that <code>n</code> are running this method
     * will not start any additional nodes.
     */
    public synchronized void ensureAtMostNumDataNodes(int n) throws IOException {
        int size = numDataNodes();
        if (size <= n) {
            return;
        }
        // prevent killing the master if possible and client nodes
        final Stream<NodeAndClient> collection = n == 0
                ? nodes.values().stream()
                : nodes.values().stream()
                        .filter(DATA_NODE_PREDICATE.and(new NodeNamePredicate(getMasterName()).negate()));
        final Iterator<NodeAndClient> values = collection.iterator();

        logger.info("changing cluster size from {} data nodes to {}", size, n);
        Set<NodeAndClient> nodesToRemove = new HashSet<>();
        int numNodesAndClients = 0;
        while (values.hasNext() && numNodesAndClients++ < size - n) {
            NodeAndClient next = values.next();
            nodesToRemove.add(next);
        }

        stopNodesAndClients(nodesToRemove);
        if (!nodesToRemove.isEmpty() && size() > 0) {
            validateClusterFormed();
        }
    }

    private Settings getNodeSettings(final int nodeId, final long seed, final Settings extraSettings) {
        final Settings settings = getSettings(nodeId, seed, extraSettings);

        final String name = buildNodeName(nodeId, settings);

        final Settings.Builder updatedSettings = Settings.builder();

        updatedSettings.put(Environment.PATH_HOME_SETTING.getKey(), baseDir);

        if (numDataPaths > 1) {
            updatedSettings.putList(Environment.PATH_DATA_SETTING.getKey(), IntStream.range(0, numDataPaths).mapToObj(i ->
                baseDir.resolve(name).resolve("d" + i).toString()).toList());
        } else {
            updatedSettings.put(Environment.PATH_DATA_SETTING.getKey(), baseDir.resolve(name));
        }

        updatedSettings.put(Environment.PATH_SHARED_DATA_SETTING.getKey(), baseDir.resolve(name + "-shared"));

        // allow overriding the above
        updatedSettings.put(settings);
        // force certain settings
        updatedSettings.put("node.name", name);
        updatedSettings.put(NodeEnvironment.NODE_ID_SEED_SETTING.getKey(), seed);

        if (autoManageMasterNodes) {
            assertThat(updatedSettings.get(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey()))
                .as("if master nodes are automatically managed then nodes must complete a join cycle when starting")
                .isNull();
        }

        return updatedSettings.build();
    }

    /**
     * builds a new node
     *
     * @param nodeId                    node ordinal
     * @param settings                  the settings to use
     * @param reuseExisting             if a node with the same name is already part of {@link #nodes}, no new node will be built and
     *                                  the method will return the existing one
     * @param onTransportServiceStarted callback to run when transport service is started
     */
    private synchronized NodeAndClient buildNode(int nodeId, Settings settings,
                                    boolean reuseExisting, Runnable onTransportServiceStarted) {
        assert Thread.holdsLock(this);
        ensureOpen();
        Collection<Class<? extends Plugin>> plugins = getPlugins();
        String name = settings.get("node.name");

        final NodeAndClient nodeAndClient = nodes.get(name);
        if (reuseExisting && nodeAndClient != null) {
            onTransportServiceStarted.run(); // reusing an existing node implies its transport service already started
            return nodeAndClient;
        }
        assert reuseExisting || nodeAndClient == null : "node name [" + name + "] already exists but not allowed to use it";

        MockNode node = new MockNode(
            settings,
            plugins,
            nodeConfigurationSource.nodeConfigPath(nodeId));
        node.injector().getInstance(TransportService.class).addLifecycleListener(new LifecycleListener() {
            @Override
            public void afterStart() {
                onTransportServiceStarted.run();
            }
        });
        return new NodeAndClient(name, node, settings, nodeId);
    }

    private String getNodePrefix(Settings settings) {
        return nodePrefix + getRoleSuffix(settings);
    }

    private String buildNodeName(int id, Settings settings) {
        return getNodePrefix(settings) + id;
    }

    /**
     * returns a suffix string based on the node role. If no explicit role is defined, the suffix will be empty
     */
    private static String getRoleSuffix(Settings settings) {
        String suffix = "";
        if (Node.NODE_MASTER_SETTING.exists(settings) && Node.NODE_MASTER_SETTING.get(settings)) {
            suffix = suffix + DiscoveryNodeRole.MASTER_ROLE.roleNameAbbreviation();
        }
        if (Node.NODE_DATA_SETTING.exists(settings) && Node.NODE_DATA_SETTING.get(settings)) {
            suffix = suffix + DiscoveryNodeRole.DATA_ROLE.roleNameAbbreviation();
        }
        if (Node.NODE_MASTER_SETTING.exists(settings) && Node.NODE_MASTER_SETTING.get(settings) == false &&
            Node.NODE_DATA_SETTING.exists(settings) && Node.NODE_DATA_SETTING.get(settings) == false
            ) {
            suffix = suffix + "c";
        }
        return suffix;
    }

    /**
     * Returns a client connected to any node in the cluster
     */
    public synchronized Client client() {
        ensureOpen();
        /* Randomly return a client to one of the nodes in the cluster */
        return getOrBuildRandomNode().client();
    }

    /**
     * Returns a node client to a data node in the cluster.
     * Note: use this with care tests should not rely on a certain nodes client.
     */
    public Client dataNodeClient() {
        /* Randomly return a client to one of the nodes in the cluster */
        return getRandomNodeAndClient(DATA_NODE_PREDICATE).client();
    }

    /**
     * Returns a node client to the current master node.
     * Note: use this with care tests should not rely on a certain nodes client.
     */
    public Client masterClient() {
        NodeAndClient randomNodeAndClient = getRandomNodeAndClient(new NodeNamePredicate(getMasterName()));
        if (randomNodeAndClient != null) {
            return randomNodeAndClient.nodeClient(); // ensure node client master is requested
        }
        throw new AssertionError("No master client found");
    }

    /**
     * Returns a node client to random node but not the master. This method will fail if no non-master client is available.
     */
    public Client nonMasterClient() {
        NodeAndClient randomNodeAndClient = getRandomNodeAndClient(new NodeNamePredicate(getMasterName()).negate());
        if (randomNodeAndClient != null) {
            return randomNodeAndClient.nodeClient(); // ensure node client non-master is requested
        }
        throw new AssertionError("No non-master client found");
    }

    /**
     * Returns a client to a coordinating only node
     */
    public synchronized Client coordOnlyNodeClient() {
        ensureOpen();
        NodeAndClient randomNodeAndClient = getRandomNodeAndClient(NO_DATA_NO_MASTER_PREDICATE);
        if (randomNodeAndClient != null) {
            return randomNodeAndClient.client();
        }
        int nodeId = nextNodeId.getAndIncrement();
        Settings settings = getSettings(nodeId, random.nextLong(), Settings.EMPTY);
        startCoordinatingOnlyNode(settings);
        return getRandomNodeAndClient(NO_DATA_NO_MASTER_PREDICATE).client();
    }

    public synchronized String startCoordinatingOnlyNode(Settings settings) {
        ensureOpen(); // currently unused
        Builder builder = Settings.builder().put(settings).put(Node.NODE_MASTER_SETTING.getKey(), false)
            .put(Node.NODE_DATA_SETTING.getKey(), false);
        return startNode(builder);
    }

    /**
     * Returns a node client to a given node.
     */
    public Client client(String nodeName) {
        NodeAndClient nodeAndClient = nodes.get(nodeName);
        if (nodeAndClient != null) {
            return nodeAndClient.client();
        }
        throw new AssertionError("No node found with name: [" + nodeName + "]");
    }


    /**
     * Returns a "smart" node client to a random node in the cluster
     */
    public Client smartClient() {
        NodeAndClient randomNodeAndClient = getRandomNodeAndClient();
        if (randomNodeAndClient != null) {
            return randomNodeAndClient.nodeClient();
        }
        throw new AssertionError("No smart client found");
    }

    /**
     * Closes the current cluster
     */
    public synchronized void close() throws IOException {
        if (this.open.compareAndSet(true, false)) {
            if (activeDisruptionScheme != null) {
                activeDisruptionScheme.testClusterClosed();
                activeDisruptionScheme = null;
            }
            try {
                IOUtils.close(nodes.values());
            } finally {
                nodes = Collections.emptyNavigableMap();
                executor.shutdownNow();
            }
        }
    }

    private final class NodeAndClient implements Closeable {
        private MockNode node;
        private final Settings originalNodeSettings;
        private Client nodeClient;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String name;
        private final int nodeAndClientId;

        NodeAndClient(String name, MockNode node, Settings originalNodeSettings, int nodeAndClientId) {
            this.node = node;
            this.name = name;
            this.originalNodeSettings = originalNodeSettings;
            this.nodeAndClientId = nodeAndClientId;
            markNodeDataDirsAsNotEligibleForWipe(node);
        }

        Node node() {
            if (closed.get()) {
                throw new RuntimeException("already closed");
            }
            return node;
        }

        public int nodeAndClientId() {
            return nodeAndClientId;
        }

        public String getName() {
            return name;
        }

        public boolean isMasterEligible() {
            return Node.NODE_MASTER_SETTING.get(node.settings());
        }

        Client client() {
            return getOrBuildNodeClient();
        }

        Client nodeClient() {
            if (closed.get()) {
                throw new RuntimeException("already closed");
            }
            return getOrBuildNodeClient();
        }

        private Client getOrBuildNodeClient() {
            synchronized (TestCluster.this) {
                if (closed.get()) {
                    throw new RuntimeException("already closed");
                }
                if (nodeClient == null) {
                    nodeClient = node.client();
                }
                return nodeClient;
            }
        }

        void resetClient() {
            if (closed.get() == false) {
                Releasables.close(nodeClient);
                nodeClient = null;
            }
        }

        void startNode() {
            boolean success = false;
            try {
                node.start();
                success = true;
            } catch (NodeValidationException e) {
                throw new RuntimeException(e);
            } finally {
                if (success == false) {
                    IOUtils.closeWhileHandlingException(node);
                }
            }
        }

        /**
         * closes the node and prepares it to be restarted
         */
        Settings closeForRestart(RestartCallback callback) throws Exception {
            assert callback != null;
            close();
            removeNode(this);
            Settings callbackSettings = callback.onNodeStopped(name);
            assert callbackSettings != null;
            Settings.Builder newSettings = Settings.builder();
            if (autoManageMasterNodes) {
                newSettings.putList(INITIAL_MASTER_NODES_SETTING.getKey());
            }
            newSettings.put(callbackSettings);
            // delete data folders now, before we start other nodes that may claim it
            clearDataIfNeeded(callback);
            return newSettings.build();
        }

        private void clearDataIfNeeded(RestartCallback callback) throws IOException {
            if (callback.clearData(name)) {
                NodeEnvironment nodeEnv = node.getNodeEnvironment();
                if (nodeEnv.hasNodeFile()) {
                    final Path[] locations = nodeEnv.nodeDataPaths();
                    logger.debug("removing node data paths: [{}]", Arrays.toString(locations));
                    IOUtils.rm(locations);
                }
            }
        }

        private void recreateNode(final Settings newSettings, final Runnable onTransportServiceStarted) {
            if (closed.get() == false) {
                throw new IllegalStateException("node " + name + " should be closed before recreating it");
            }
            // use a new seed to make sure we generate a fresh new node id if the data folder has been wiped
            final long newIdSeed = NodeEnvironment.NODE_ID_SEED_SETTING.get(node.settings()) + 1;
            Settings finalSettings = Settings.builder()
                    .put(originalNodeSettings)
                    .put(newSettings)
                    .put(NodeEnvironment.NODE_ID_SEED_SETTING.getKey(), newIdSeed)
                    .build();
            Collection<Class<? extends Plugin>> plugins = node.getClasspathPlugins();
            node = new MockNode(finalSettings, plugins);
            node.injector().getInstance(TransportService.class).addLifecycleListener(new LifecycleListener() {
                @Override
                public void afterStart() {
                    onTransportServiceStarted.run();
                }
            });
            closed.set(false);
            markNodeDataDirsAsNotEligibleForWipe(node);
        }

        @Override
        public void close() throws IOException {
            assert Thread.holdsLock(TestCluster.this);
            try {
                resetClient();
            } finally {
                closed.set(true);
                markNodeDataDirsAsPendingForWipe(node);
                node.close();
                try {
                    if (node.awaitClose(10, TimeUnit.SECONDS) == false) {
                        throw new AssertionError("Node didn't close within 10 seconds.");
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError("Interruption while waiting for the node to close", e);
                }
            }
        }

        private void markNodeDataDirsAsPendingForWipe(Node node) {
            assert Thread.holdsLock(TestCluster.this);
            NodeEnvironment nodeEnv = node.getNodeEnvironment();
            if (nodeEnv.hasNodeFile()) {
                dataDirToClean.addAll(Arrays.asList(nodeEnv.nodeDataPaths()));
            }
        }

        private void markNodeDataDirsAsNotEligibleForWipe(Node node) {
            assert Thread.holdsLock(TestCluster.this);
            NodeEnvironment nodeEnv = node.getNodeEnvironment();
            if (nodeEnv.hasNodeFile()) {
                dataDirToClean.removeAll(Arrays.asList(nodeEnv.nodeDataPaths()));
            }
        }
    }

    private synchronized void reset(boolean wipeData) throws IOException {
        // clear all rules for mock transport services
        for (NodeAndClient nodeAndClient : nodes.values()) {
            TransportService transportService = nodeAndClient.node.injector().getInstance(TransportService.class);
            if (transportService instanceof MockTransportService) {
                final MockTransportService mockTransportService = (MockTransportService) transportService;
                mockTransportService.clearAllRules();
            }
        }
        randomlyResetClients();
        final int newSize = sharedNodesSeeds.length;
        if (nextNodeId.get() == newSize && nodes.size() == newSize) {
            if (wipeData) {
                wipePendingDataDirectories();
            }
            logger.debug("Cluster hasn't changed - moving out - nodes: [{}] nextNodeId: [{}] numSharedNodes: [{}]",
                    nodes.keySet(), nextNodeId.get(), newSize);
            return;
        }
        logger.debug("Cluster is NOT consistent - restarting shared nodes - nodes: [{}] nextNodeId: [{}] numSharedNodes: [{}]",
                nodes.keySet(), nextNodeId.get(), newSize);

        // trash all nodes with id >= sharedNodesSeeds.length - they are non shared
        final List<NodeAndClient> toClose = new ArrayList<>();
        for (NodeAndClient nodeAndClient : nodes.values()) {
            if (nodeAndClient.nodeAndClientId() >= sharedNodesSeeds.length) {
                logger.debug("Close Node [{}] not shared", nodeAndClient.name);
                toClose.add(nodeAndClient);
            }
        }
        stopNodesAndClients(toClose);

        // clean up what the nodes left that is unused
        if (wipeData) {
            wipePendingDataDirectories();
        }

        assertThat(nodes.isEmpty() || nodes.values().stream().anyMatch(NodeAndClient::isMasterEligible))
            .as("expected at least one master-eligible node left in " + nodes)
            .isTrue();

        final int prevNodeCount = nodes.size();

        // start any missing node
        assert newSize == numSharedDedicatedMasterNodes + numSharedDataNodes + numSharedCoordOnlyNodes;
        final List<NodeAndClient> toStartAndPublish = new ArrayList<>(); // we want to start nodes in one go
        final Runnable onTransportServiceStarted = () -> rebuildUnicastHostFiles(toStartAndPublish);

        final List<Settings> settings = new ArrayList<>();

        for (int i = 0; i < numSharedDedicatedMasterNodes; i++) {
            final Settings.Builder extraSettings = Settings.builder();
            extraSettings.put(Node.NODE_MASTER_SETTING.getKey(), true);
            extraSettings.put(Node.NODE_DATA_SETTING.getKey(), false);
            settings.add(getNodeSettings(i, sharedNodesSeeds[i], extraSettings.build()));
        }
        for (int i = numSharedDedicatedMasterNodes; i < numSharedDedicatedMasterNodes + numSharedDataNodes; i++) {
            final Settings.Builder extraSettings = Settings.builder();
            if (numSharedDedicatedMasterNodes > 0) {
                // if we don't have dedicated master nodes, keep things default
                extraSettings.put(Node.NODE_MASTER_SETTING.getKey(), false).build();
                extraSettings.put(Node.NODE_DATA_SETTING.getKey(), true).build();
            }
            settings.add(getNodeSettings(i, sharedNodesSeeds[i], extraSettings.build()));
        }
        for (int i = numSharedDedicatedMasterNodes + numSharedDataNodes;
             i < numSharedDedicatedMasterNodes + numSharedDataNodes + numSharedCoordOnlyNodes; i++) {
            final Builder extraSettings = Settings.builder().put(Node.NODE_MASTER_SETTING.getKey(), false)
                .put(Node.NODE_DATA_SETTING.getKey(), false);
            settings.add(getNodeSettings(i, sharedNodesSeeds[i], extraSettings.build()));
        }

        int autoBootstrapMasterNodeIndex = -1;
        final List<String> masterNodeNames = settings.stream()
                .filter(Node.NODE_MASTER_SETTING::get)
                .map(Node.NODE_NAME_SETTING::get)
                .collect(Collectors.toList());

        if (prevNodeCount == 0 && autoManageMasterNodes) {
            if (numSharedDedicatedMasterNodes > 0) {
                autoBootstrapMasterNodeIndex = RandomNumbers.randomIntBetween(random, 0, numSharedDedicatedMasterNodes - 1);
            } else if (numSharedDataNodes > 0) {
                autoBootstrapMasterNodeIndex = RandomNumbers.randomIntBetween(random, 0, numSharedDataNodes - 1);
            }
        }

        final List<Settings> updatedSettings = bootstrapMasterNodeWithSpecifiedIndex(settings);

        for (int i = 0; i < numSharedDedicatedMasterNodes + numSharedDataNodes + numSharedCoordOnlyNodes; i++) {
            Settings nodeSettings = updatedSettings.get(i);
            if (i == autoBootstrapMasterNodeIndex) {
                nodeSettings = Settings.builder().putList(INITIAL_MASTER_NODES_SETTING.getKey(), masterNodeNames).put(nodeSettings).build();
            }
            final NodeAndClient nodeAndClient = buildNode(i, nodeSettings, true, onTransportServiceStarted);
            toStartAndPublish.add(nodeAndClient);
        }

        startAndPublishNodesAndClients(toStartAndPublish);

        nextNodeId.set(newSize);
        assert size() == newSize;
        if (autoManageMasterNodes && newSize > 0) {
            validateClusterFormed();
        }
        logger.debug("Cluster is consistent again - nodes: [{}] nextNodeId: [{}] numSharedNodes: [{}]",
                nodes.keySet(), nextNodeId.get(), newSize);
    }

    /** ensure a cluster is formed with all published nodes. */
    public synchronized void validateClusterFormed() {
        final Set<DiscoveryNode> expectedNodes = new HashSet<>();
        for (NodeAndClient nodeAndClient : nodes.values()) {
            expectedNodes.add(getInstanceFromNode(ClusterService.class, nodeAndClient.node()).localNode());
        }
        logger.trace("validating cluster formed, expecting {}", expectedNodes);

        try {
            assertBusy(() -> {
                final List<ClusterState> states = nodes.values().stream()
                    .map(node -> getInstanceFromNode(ClusterService.class, node.node()))
                    .map(ClusterService::state)
                    .toList();
                final String debugString = ", expected nodes: " + expectedNodes + " and actual cluster states " + states;
                // all nodes have a master
                assertThat(states.stream().allMatch(cs -> cs.nodes().getMasterNodeId() != null))
                    .as("Missing master" + debugString)
                    .isTrue();
                // all nodes have the same master (in same term)
                assertThat(states.stream().mapToLong(ClusterState::term).distinct().count())
                    .as("Not all masters in same term" + debugString)
                    .isEqualTo(1);
                // all nodes know about all other nodes
                states.forEach(cs -> {
                    DiscoveryNodes discoveryNodes = cs.nodes();
                    assertThat(expectedNodes)
                        .as("Node size mismatch" + debugString)
                        .hasSize(discoveryNodes.getSize());
                    for (DiscoveryNode expectedNode : expectedNodes) {
                        assertThat(discoveryNodes.nodeExists(expectedNode))
                            .as("Expected node to exist: " + expectedNode + debugString)
                            .isTrue();
                    }
                });
            }, 30, TimeUnit.SECONDS);
        } catch (AssertionError ae) {
            throw new IllegalStateException("cluster failed to form", ae);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * This method should be executed during tear down, after each test (but after assertAfterTest)
     */
    public synchronized void afterTest() {
        wipePendingDataDirectories();
        randomlyResetClients(); /* reset all clients - each test gets its own client based on the Random instance created above. */
    }

    /**
     * Assertions that should run before the cluster is wiped should be called in this method
     */
    public void beforeIndexDeletion() throws Exception {
        // Check that the operations counter on index shard has reached 0.
        // The assumption here is that after a test there are no ongoing write operations.
        // test that have ongoing write operations after the test (for example because ttl is used
        // and not all docs have been purged after the test) and inherit from
        // ElasticsearchIntegrationTest must override beforeIndexDeletion() to avoid failures.
        assertNoPendingIndexOperations();
        //check that shards that have same sync id also contain same number of documents
        assertSameSyncIdSameDocs();
        assertOpenTranslogReferences();
    }

    private void assertSameSyncIdSameDocs() {
        Map<String, Long> docsOnShards = new HashMap<>();
        final Collection<NodeAndClient> nodesAndClients = nodes.values();
        for (NodeAndClient nodeAndClient : nodesAndClients) {
            IndicesService indexServices = getInstance(IndicesService.class, nodeAndClient.name);
            for (IndexService indexService : indexServices) {
                for (IndexShard indexShard : indexService) {
                    try {
                        CommitStats commitStats = indexShard.commitStats();
                        String syncId = commitStats.getUserData().get(Engine.SYNC_COMMIT_ID);
                        if (syncId != null) {
                            long liveDocsOnShard = commitStats.getNumDocs();
                            if (docsOnShards.get(syncId) != null) {
                                assertThat(docsOnShards.get(syncId))
                                    .as("sync id is equal but number of docs does not match on node " + nodeAndClient.name)
                                    .isEqualTo(liveDocsOnShard);
                            } else {
                                docsOnShards.put(syncId, liveDocsOnShard);
                            }
                        }
                    } catch (AlreadyClosedException e) {
                        // the engine is closed or if the shard is recovering
                    }
                }
            }
        }
    }

    private void assertNoPendingIndexOperations() throws Exception {
        assertBusy(() -> {
            for (NodeAndClient nodeAndClient : nodes.values()) {
                IndicesService indexServices = getInstance(IndicesService.class, nodeAndClient.name);
                for (IndexService indexService : indexServices) {
                    for (IndexShard indexShard : indexService) {
                        List<String> operations = indexShard.getActiveOperations();
                        if (!operations.isEmpty()) {
                            throw new AssertionError(
                                "shard " + indexShard.shardId() + " on node [" + nodeAndClient.name + "] has pending operations:\n --> " +
                                    String.join("\n --> ", operations)
                            );
                        }
                    }
                }
            }
        }, 60, TimeUnit.SECONDS);
    }

    private void assertOpenTranslogReferences() throws Exception {
        assertBusy(() -> {
            for (NodeAndClient nodeAndClient : nodes.values()) {
                IndicesService indexServices = getInstance(IndicesService.class, nodeAndClient.name);
                for (IndexService indexService : indexServices) {
                    for (IndexShard indexShard : indexService) {
                        try {
                            if (IndexShardTestCase.getEngine(indexShard) instanceof InternalEngine) {
                                IndexShardTestCase.getTranslog(indexShard).getDeletionPolicy().assertNoOpenTranslogRefs();
                            }
                        } catch (AlreadyClosedException ok) {
                            // all good
                        }
                    }
                }
            }
        }, 60, TimeUnit.SECONDS);
    }

    /**
     * Asserts that the document history in Lucene index is consistent with Translog's on every index shard of the cluster.
     * This assertion might be expensive, thus we prefer not to execute on every test but only interesting tests.
     */
    public void assertConsistentHistoryBetweenTranslogAndLuceneIndex() throws IOException {
        for (NodeAndClient nodeAndClient : nodes.values()) {
            IndicesService indexServices = getInstance(IndicesService.class, nodeAndClient.name);
            for (IndexService indexService : indexServices) {
                for (IndexShard indexShard : indexService) {
                    try {
                        IndexShardTestCase.assertConsistentHistoryBetweenTranslogAndLucene(indexShard);
                    } catch (AlreadyClosedException ignored) {
                        // shard is closed
                    }
                }
            }
        }
    }

    public void assertNoInFlightDocsInEngine() throws Exception {
        assertBusy(() -> {
            for (String nodeName : getNodeNames()) {
                IndicesService indexServices = getInstance(IndicesService.class, nodeName);
                for (IndexService indexService : indexServices) {
                    for (IndexShard indexShard : indexService) {
                        try {
                            final Engine engine = IndexShardTestCase.getEngine(indexShard);
                            assertThat(EngineTestCase.getInFlightDocCount(engine))
                                .as(indexShard.routingEntry().toString())
                                .isEqualTo(0L);
                        } catch (AlreadyClosedException ignored) {
                            // shard is closed
                        }
                    }
                }
            }
        });
    }

    private IndexShard getShardOrNull(ClusterState clusterState, ShardRouting shardRouting) {
        if (shardRouting == null || shardRouting.assignedToNode() == false) {
            return null;
        }
        final DiscoveryNode assignedNode = clusterState.nodes().get(shardRouting.currentNodeId());
        if (assignedNode == null) {
            return null;
        }
        return getInstance(IndicesService.class, assignedNode.getName()).getShardOrNull(shardRouting.shardId());
    }

    public void assertSeqNos() throws Exception {
        assertBusy(() -> {
            final ClusterState state = clusterService().state();
            for (ObjectObjectCursor<String, IndexRoutingTable> indexRoutingTable : state.routingTable().indicesRouting()) {
                for (IntObjectCursor<IndexShardRoutingTable> indexShardRoutingTable : indexRoutingTable.value.shards()) {
                    ShardRouting primaryShardRouting = indexShardRoutingTable.value.primaryShard();
                    final IndexShard primaryShard = getShardOrNull(state, primaryShardRouting);
                    if (primaryShard == null) {
                        continue; //just ignore - shard movement
                    }
                    final SeqNoStats primarySeqNoStats;
                    final ObjectLongMap<String> syncGlobalCheckpoints;
                    try {
                        primarySeqNoStats = primaryShard.seqNoStats();
                        syncGlobalCheckpoints = primaryShard.getInSyncGlobalCheckpoints();
                    } catch (AlreadyClosedException ex) {
                        continue; // shard is closed - just ignore
                    }
                    assertThat(primarySeqNoStats.getGlobalCheckpoint())
                        .as(primaryShardRouting + " should have set the global checkpoint")
                        .isNotEqualTo(SequenceNumbers.UNASSIGNED_SEQ_NO);
                    for (ShardRouting replicaShardRouting : indexShardRoutingTable.value.replicaShards()) {
                        final IndexShard replicaShard = getShardOrNull(state, replicaShardRouting);
                        if (replicaShard == null) {
                            continue; //just ignore - shard movement
                        }
                        final SeqNoStats seqNoStats;
                        try {
                            seqNoStats = replicaShard.seqNoStats();
                        } catch (AlreadyClosedException e) {
                            continue; // shard is closed - just ignore
                        }
                        assertThat(seqNoStats)
                            .as(replicaShardRouting + " seq_no_stats mismatch")
                            .isEqualTo(primarySeqNoStats);
                        // the local knowledge on the primary of the global checkpoint equals the global checkpoint on the shard
                        assertThat(seqNoStats.getGlobalCheckpoint())
                            .as(replicaShardRouting + " global checkpoint syncs mismatch")
                            .isEqualTo(syncGlobalCheckpoints.get(replicaShardRouting.allocationId().getId()));
                    }
                }
            }
        }, 60, TimeUnit.SECONDS);
    }

    /**
     * Asserts that all shards with the same shardId should have document Ids.
     */
    public void assertSameDocIdsOnShards() throws Exception {
        assertBusy(() -> {
            ClusterState state = client().state(new ClusterStateRequest()).get().getState();
            for (ObjectObjectCursor<String, IndexRoutingTable> indexRoutingTable : state.routingTable().indicesRouting()) {
                for (IntObjectCursor<IndexShardRoutingTable> indexShardRoutingTable : indexRoutingTable.value.shards()) {
                    ShardRouting primaryShardRouting = indexShardRoutingTable.value.primaryShard();
                    IndexShard primaryShard = getShardOrNull(state, primaryShardRouting);
                    if (primaryShard == null) {
                        continue;
                    }
                    final List<DocIdAndSeqNo> docsOnPrimary;
                    try {
                        docsOnPrimary = IndexShardTestCase.getDocIdAndSeqNos(primaryShard);
                    } catch (AlreadyClosedException ex) {
                        continue;
                    }
                    for (ShardRouting replicaShardRouting : indexShardRoutingTable.value.replicaShards()) {
                        IndexShard replicaShard = getShardOrNull(state, replicaShardRouting);
                        if (replicaShard == null) {
                            continue;
                        }
                        final List<DocIdAndSeqNo> docsOnReplica;
                        try {
                            docsOnReplica = IndexShardTestCase.getDocIdAndSeqNos(replicaShard);
                        } catch (AlreadyClosedException ex) {
                            continue;
                        }
                        assertThat(docsOnReplica)
                            .as("out of sync shards: primary=[" + primaryShardRouting + "] num_docs_on_primary=[" + docsOnPrimary.size()
                                + "] vs replica=[" + replicaShardRouting + "] num_docs_on_replica=[" + docsOnReplica.size() + "]")
                            .isEqualTo(docsOnPrimary);
                    }
                }
            }
        });
    }

    private void randomlyResetClients() {
        assert Thread.holdsLock(this);
        // only reset the clients on nightly tests, it causes heavy load...
        if (RandomizedTest.isNightly() && rarely(random)) {
            final Collection<NodeAndClient> nodesAndClients = nodes.values();
            for (NodeAndClient nodeAndClient : nodesAndClients) {
                nodeAndClient.resetClient();
            }
        }
    }

    public synchronized void wipePendingDataDirectories() {
        if (!dataDirToClean.isEmpty()) {
            try {
                for (Path path : dataDirToClean) {
                    try {
                        FileSystemUtils.deleteSubDirectories(path);
                        logger.info("Successfully wiped data directory for node location: {}", path);
                    } catch (IOException e) {
                        logger.info("Failed to wipe data directory for node location: {}", path);
                    }
                }
            } finally {
                dataDirToClean.clear();
            }
        }
    }

    /**
     * Returns a reference to a random node's {@link ClusterService}
     */
    public ClusterService clusterService() {
        return clusterService(null);
    }

    /**
     * Returns a reference to a node's {@link ClusterService}. If the given node is null, a random node will be selected.
     */
    public ClusterService clusterService(@Nullable String node) {
        return getInstance(ClusterService.class, node);
    }

    /**
     * Returns an Iterable to all instances for the given class &gt;T&lt; across all nodes in the cluster.
     */
    public <T> Iterable<T> getInstances(Class<T> clazz) {
        return nodes.values().stream().map(node -> getInstanceFromNode(clazz, node.node)).toList();
    }

    /**
     * Returns an Iterable to all instances for the given class &gt;T&lt; across all data nodes in the cluster.
     */
    public <T> Iterable<T> getDataNodeInstances(Class<T> clazz) {
        return getInstances(clazz, DATA_NODE_PREDICATE);
    }

    public synchronized <T> T getCurrentMasterNodeInstance(Class<T> clazz) {
        return getInstance(clazz, new NodeNamePredicate(getMasterName()));
    }

    /**
     * Returns an Iterable to all instances for the given class &gt;T&lt; across all data and master nodes
     * in the cluster.
     */
    public <T> Iterable<T> getDataOrMasterNodeInstances(Class<T> clazz) {
        return getInstances(clazz, DATA_NODE_PREDICATE.or(MASTER_NODE_PREDICATE));
    }

    private <T> Iterable<T> getInstances(Class<T> clazz, Predicate<NodeAndClient> predicate) {
        Iterable<NodeAndClient> filteredNodes = nodes.values().stream().filter(predicate)::iterator;
        List<T> instances = new ArrayList<>();
        for (NodeAndClient nodeAndClient : filteredNodes) {
            instances.add(getInstanceFromNode(clazz, nodeAndClient.node));
        }
        return instances;
    }

    /**
     * Returns a reference to the given nodes instances of the given class &gt;T&lt;
     */
    public <T> T getInstance(Class<T> clazz, final String node) {
        return getInstance(clazz, nc -> node == null || node.equals(nc.name));
    }

    public <T> T getDataNodeInstance(Class<T> clazz) {
        return getInstance(clazz, DATA_NODE_PREDICATE);
    }

    public <T> T getMasterNodeInstance(Class<T> clazz) {
        return getInstance(clazz, MASTER_NODE_PREDICATE);
    }

    private synchronized <T> T getInstance(Class<T> clazz, Predicate<NodeAndClient> predicate) {
        NodeAndClient randomNodeAndClient = getRandomNodeAndClient(predicate);
        assert randomNodeAndClient != null : "There must be a client matching the given predicate";
        return getInstanceFromNode(clazz, randomNodeAndClient.node);
    }

    /**
     * Returns a reference to a random nodes instances of the given class &gt;T&lt;
     */
    public <T> T getInstance(Class<T> clazz) {
        return getInstance(clazz, nc -> true);
    }

    private static <T> T getInstanceFromNode(Class<T> clazz, Node node) {
        return node.injector().getInstance(clazz);
    }

    public Settings dataPathSettings(String node) {
        return nodes.values()
            .stream()
            .filter(nc -> nc.name.equals(node))
            .findFirst().get().node().settings()
            .filter(key -> key.equals(Environment.PATH_DATA_SETTING.getKey()) ||  key.equals(Environment.PATH_SHARED_DATA_SETTING.getKey()));
    }


    /**
     * Returns the number of nodes in the cluster.
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Returns the http addresses of the nodes within the cluster.
     * Can be used to run REST tests against the test cluster.
     */
    public InetSocketAddress[] httpAddresses() {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (HttpServerTransport httpServerTransport : getInstances(HttpServerTransport.class)) {
            addresses.add(httpServerTransport.boundAddress().publishAddress().address());
        }
        return addresses.toArray(new InetSocketAddress[0]);
    }

    /**
     * Stops a random data node in the cluster. Returns true if a node was found to stop, false otherwise.
     */
    public synchronized boolean stopRandomDataNode() throws IOException {
        ensureOpen();
        NodeAndClient nodeAndClient = getRandomNodeAndClient(DATA_NODE_PREDICATE);
        if (nodeAndClient != null) {
            logger.info("Closing random node [{}] ", nodeAndClient.name);
            stopNodesAndClient(nodeAndClient);
            return true;
        }
        return false;
    }

    /**
     * Stops a random node in the cluster that applies to the given filter or non if the non of the nodes applies to the
     * filter.
     */
    public synchronized void stopRandomNode(final Predicate<Settings> filter) throws IOException {
        ensureOpen();
        NodeAndClient nodeAndClient = getRandomNodeAndClient(nc -> filter.test(nc.node.settings()));
        if (nodeAndClient != null) {
            if (nodeAndClient.nodeAndClientId() < sharedNodesSeeds.length && nodeAndClient.isMasterEligible() && autoManageMasterNodes
                && nodes.values().stream()
                        .filter(NodeAndClient::isMasterEligible)
                        .filter(n -> n.nodeAndClientId() < sharedNodesSeeds.length)
                        .count() == 1) {
                throw new AssertionError("Tried to stop the only master eligible shared node");
            }
            logger.info("Closing filtered random node [{}] ", nodeAndClient.name);
            stopNodesAndClient(nodeAndClient);
        }
    }

    /**
     * Stops the current master node forcefully
     */
    public synchronized void stopCurrentMasterNode() throws IOException {
        ensureOpen();
        assert size() > 0;
        String masterNodeName = getMasterName();
        final NodeAndClient masterNode = nodes.get(masterNodeName);
        assert masterNode != null;
        logger.info("Closing master node [{}] ", masterNodeName);
        stopNodesAndClient(masterNode);
    }

    /**
     * Stops any of the current nodes but not the master node.
     */
    public synchronized void stopRandomNonMasterNode() throws IOException {
        NodeAndClient nodeAndClient = getRandomNodeAndClient(new NodeNamePredicate(getMasterName()).negate());
        if (nodeAndClient != null) {
            logger.info("Closing random non master node [{}] current master [{}] ", nodeAndClient.name, getMasterName());
            stopNodesAndClient(nodeAndClient);
        }
    }

    private synchronized void startAndPublishNodesAndClients(List<NodeAndClient> nodeAndClients) {
        if (!nodeAndClients.isEmpty()) {
            final int newMasters = (int) nodeAndClients.stream().filter(NodeAndClient::isMasterEligible)
                .filter(nac -> nodes.containsKey(nac.name) == false) // filter out old masters
                .count();
            rebuildUnicastHostFiles(nodeAndClients); // ensure that new nodes can find the existing nodes when they start
            var futures = nodeAndClients.stream().map(node -> executor.submit(node::startNode)).toList();

            try {
                for (Future<?> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                throw new AssertionError("interrupted while starting nodes", e);
            } catch (ExecutionException e) {
                RuntimeException re = FutureUtils.rethrowExecutionException(e);
                re.addSuppressed(new RuntimeException("failed to start nodes"));
                throw re;
            }
            nodeAndClients.forEach(this::publishNode);

            if (autoManageMasterNodes && newMasters > 0) {
                // update once masters have joined
                validateClusterFormed();
            }
        }
    }

    private final Object discoveryFileMutex = new Object();

    private void rebuildUnicastHostFiles(List<NodeAndClient> newNodes) {
        // cannot be a synchronized method since it's called on other threads from within synchronized startAndPublishNodesAndClients()
        synchronized (discoveryFileMutex) {
            try {
                final Collection<NodeAndClient> currentNodes = nodes.values();
                Stream<NodeAndClient> unicastHosts = Stream.concat(currentNodes.stream(), newNodes.stream());
                List<String> discoveryFileContents = unicastHosts.map(
                    nac -> nac.node.injector().getInstance(TransportService.class)
                ).filter(Objects::nonNull)
                    .map(TransportService::getLocalNode).filter(Objects::nonNull).filter(DiscoveryNode::isMasterEligibleNode)
                    .map(n -> n.getAddress().toString())
                    .distinct().toList();
                Set<Path> configPaths = Stream.concat(currentNodes.stream(), newNodes.stream())
                    .map(nac -> nac.node.getEnvironment().configFile()).collect(Collectors.toSet());
                logger.debug("configuring discovery with {} at {}", discoveryFileContents, configPaths);
                for (final Path configPath : configPaths) {
                    Files.createDirectories(configPath);
                    Files.write(configPath.resolve(FileBasedSeedHostsProvider.UNICAST_HOSTS_FILE), discoveryFileContents);
                }
            } catch (IOException e) {
                throw new AssertionError("failed to configure file-based discovery", e);
            }
        }
    }

    private void stopNodesAndClient(NodeAndClient nodeAndClient) throws IOException {
        stopNodesAndClients(Collections.singleton(nodeAndClient));
    }

    private synchronized void stopNodesAndClients(Collection<NodeAndClient> nodeAndClients) throws IOException {
        final Set<String> excludedNodeIds = excludeMasters(nodeAndClients);

        for (NodeAndClient nodeAndClient: nodeAndClients) {
            removeDisruptionSchemeFromNode(nodeAndClient);
            final NodeAndClient previous = removeNode(nodeAndClient);
            assert previous == nodeAndClient;
            nodeAndClient.close();
        }

        removeExclusions(excludedNodeIds);
    }

    /**
     * Restarts a random data node in the cluster
     */
    public void restartRandomDataNode() throws Exception {
        restartRandomDataNode(EMPTY_CALLBACK);
    }

    /**
     * Restarts a random data node in the cluster and calls the callback during restart.
     */
    public synchronized void restartRandomDataNode(RestartCallback callback) throws Exception {
        ensureOpen();
        NodeAndClient nodeAndClient = getRandomNodeAndClient(TestCluster.DATA_NODE_PREDICATE);
        if (nodeAndClient != null) {
            restartNode(nodeAndClient, callback);
        }
    }


    /**
     * Restarts a node and calls the callback during restart.
     */
    public synchronized void restartNode(String nodeName, RestartCallback callback) throws Exception {
        ensureOpen();
        NodeAndClient nodeAndClient = nodes.get(nodeName);
        if (nodeAndClient != null) {
            restartNode(nodeAndClient, callback);
        }
    }

    public static final RestartCallback EMPTY_CALLBACK = new RestartCallback();

    /**
     * Restarts all nodes in the cluster. It first stops all nodes and then restarts all the nodes again.
     */
    public void fullRestart() throws Exception {
        fullRestart(EMPTY_CALLBACK);
    }

    /**
     * Restarts all nodes in a rolling restart fashion ie. only restarts on node a time.
     */
    public synchronized void rollingRestart(RestartCallback callback) throws Exception {
        int numNodesRestarted = 0;
        for (NodeAndClient nodeAndClient : nodes.values()) {
            callback.doAfterNodes(numNodesRestarted++, nodeAndClient.nodeClient());
            restartNode(nodeAndClient, callback);
        }
    }

    private void restartNode(NodeAndClient nodeAndClient, RestartCallback callback) throws Exception {
        assert Thread.holdsLock(this);
        logger.info("Restarting node [{}] ", nodeAndClient.name);

        if (activeDisruptionScheme != null) {
            activeDisruptionScheme.removeFromNode(nodeAndClient.name, this);
        }

        final Set<String> excludedNodeIds = excludeMasters(Collections.singleton(nodeAndClient));
        final Settings newSettings = nodeAndClient.closeForRestart(callback);
        removeExclusions(excludedNodeIds);

        nodeAndClient.recreateNode(newSettings, () -> rebuildUnicastHostFiles(Collections.singletonList(nodeAndClient)));
        nodeAndClient.startNode();
        publishNode(nodeAndClient);

        if (callback.validateClusterForming() || excludedNodeIds.isEmpty() == false) {
            // we have to validate cluster size to ensure that the restarted node has rejoined the cluster if it was master-eligible;
            validateClusterFormed();
        }
    }

    private NodeAndClient removeNode(NodeAndClient nodeAndClient) {
        assert Thread.holdsLock(this);
        final NavigableMap<String, NodeAndClient> newNodes = new TreeMap<>(nodes);
        final NodeAndClient previous = newNodes.remove(nodeAndClient.name);
        nodes = Collections.unmodifiableNavigableMap(newNodes);
        return previous;
    }

    private Set<String> excludeMasters(Collection<NodeAndClient> nodeAndClients) {
        assert Thread.holdsLock(this);
        final Set<String> excludedNodeNames = new HashSet<>();
        if (autoManageMasterNodes && !nodeAndClients.isEmpty()) {

            final long currentMasters = nodes.values().stream().filter(NodeAndClient::isMasterEligible).count();
            final long stoppingMasters = nodeAndClients.stream().filter(NodeAndClient::isMasterEligible).count();

            assert stoppingMasters <= currentMasters : currentMasters + " < " + stoppingMasters;
            if (stoppingMasters != currentMasters && stoppingMasters > 0) {
                // If stopping few enough master-nodes that there's still a majority left, there is no need to withdraw their votes first.
                // However, we do not yet have a way to be sure there's a majority left, because the voting configuration may not yet have
                // been updated when the previous nodes shut down, so we must always explicitly withdraw votes.
                // TODO add cluster health API to check that voting configuration is optimal so this isn't always needed
                nodeAndClients.stream().filter(NodeAndClient::isMasterEligible).map(NodeAndClient::getName).forEach(excludedNodeNames::add);
                assert excludedNodeNames.size() == stoppingMasters;

                logger.info("adding voting config exclusions {} prior to restart/shutdown", excludedNodeNames);
                try {
                    client().execute(TransportAddVotingConfigExclusions.ACTION,
                            new AddVotingConfigExclusionsRequest(excludedNodeNames.toArray(Strings.EMPTY_ARRAY))).get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new AssertionError("unexpected", e);
                }
            }
        }
        return excludedNodeNames;
    }

    private void removeExclusions(Set<String> excludedNodeIds) {
        assert Thread.holdsLock(this);
        if (excludedNodeIds.isEmpty() == false) {
            logger.info("removing voting config exclusions for {} after restart/shutdown", excludedNodeIds);
            try {
                Client client = getRandomNodeAndClient(node -> excludedNodeIds.contains(node.name) == false).client();
                client.execute(TransportClearVotingConfigExclusions.ACTION, new ClearVotingConfigExclusionsRequest()).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new AssertionError("unexpected", e);
            }
        }
    }

    /**
     * Restarts all nodes in the cluster. It first stops all nodes and then restarts all the nodes again.
     */
    public synchronized void fullRestart(RestartCallback callback) throws Exception {
        int numNodesRestarted = 0;
        final Settings[] newNodeSettings = new Settings[nextNodeId.get()];
        final List<NodeAndClient> toStartAndPublish = new ArrayList<>(); // we want to start nodes in one go
        for (NodeAndClient nodeAndClient : nodes.values()) {
            callback.doAfterNodes(numNodesRestarted++, nodeAndClient.nodeClient());
            logger.info("Stopping and resetting node [{}] ", nodeAndClient.name);
            if (activeDisruptionScheme != null) {
                activeDisruptionScheme.removeFromNode(nodeAndClient.name, this);
            }
            final Settings newSettings = nodeAndClient.closeForRestart(callback);
            newNodeSettings[nodeAndClient.nodeAndClientId()] = newSettings;
            toStartAndPublish.add(nodeAndClient);
        }


        callback.onAllNodesStopped();

        // randomize start up order
        Randomness.shuffle(toStartAndPublish);

        for (NodeAndClient nodeAndClient : toStartAndPublish) {
            logger.info("recreating node [{}] ", nodeAndClient.name);
            nodeAndClient.recreateNode(newNodeSettings[nodeAndClient.nodeAndClientId()], () -> rebuildUnicastHostFiles(toStartAndPublish));

        }
        startAndPublishNodesAndClients(toStartAndPublish);

        if (callback.validateClusterForming()) {
            validateClusterFormed();
        }
    }

    /**
     * Returns the name of the current master node in the cluster.
     */
    public String getMasterName() {
        return getMasterName(null);
    }

    /**
     * Returns the name of the current master node in the cluster and executes the request via the node specified
     * in the viaNode parameter. If viaNode isn't specified a random node will be picked to the send the request to.
     */
    public String getMasterName(@Nullable String viaNode) {
        try {
            Client client = viaNode != null ? client(viaNode) : client();
            return client.state(new ClusterStateRequest()).get().getState().nodes().getMasterNode().getName();
        } catch (Exception e) {
            logger.warn("Can't fetch cluster state", e);
            throw new RuntimeException("Can't get master node " + e.getMessage(), e);
        }
    }

    synchronized Set<String> allDataNodesButN(int count) {
        final int numNodes = numDataNodes() - count;
        assert size() >= numNodes;
        Map<String, NodeAndClient> dataNodes =
            nodes
                .entrySet()
                .stream()
                .filter(entry -> DATA_NODE_PREDICATE.test(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        final HashSet<String> set = new HashSet<>();
        final Iterator<String> iterator = dataNodes.keySet().iterator();
        for (int i = 0; i < numNodes; i++) {
            assert iterator.hasNext();
            set.add(iterator.next());
        }
        return set;
    }

    /**
     * Returns a set of nodes that have at least one shard of the given index.
     */
    public synchronized Set<String> nodesInclude(String indexUUID) {
        if (clusterService().state().routingTable().hasIndex(indexUUID)) {
            List<ShardRouting> allShards = clusterService().state().routingTable().allShards(indexUUID);
            DiscoveryNodes discoveryNodes = clusterService().state().nodes();
            Set<String> nodes = new HashSet<>();
            for (ShardRouting shardRouting : allShards) {
                if (shardRouting.assignedToNode()) {
                    DiscoveryNode discoveryNode = discoveryNodes.get(shardRouting.currentNodeId());
                    nodes.add(discoveryNode.getName());
                }
            }
            return nodes;
        }
        return Collections.emptySet();
    }

    /**
     * Performs cluster bootstrap when node with index {@link #bootstrapMasterNodeIndex} is started
     * with the names of all existing and new master-eligible nodes.
     * Indexing starts from 0.
     * If {@link #bootstrapMasterNodeIndex} is -1 (default), this method does nothing.
     */
    private List<Settings> bootstrapMasterNodeWithSpecifiedIndex(List<Settings> allNodesSettings) {
        assert Thread.holdsLock(this);
        if (bootstrapMasterNodeIndex == -1) { // fast-path
            return allNodesSettings;
        }

        int currentNodeId = numMasterNodes() - 1;
        List<Settings> newSettings = new ArrayList<>();

        for (Settings settings : allNodesSettings) {
            if (Node.NODE_MASTER_SETTING.get(settings) == false) {
                newSettings.add(settings);
            } else {
                currentNodeId++;
                if (currentNodeId != bootstrapMasterNodeIndex) {
                    newSettings.add(settings);
                } else {
                    List<String> nodeNames = new ArrayList<>();

                    for (Settings nodeSettings : getDataOrMasterNodeInstances(Settings.class)) {
                        if (Node.NODE_MASTER_SETTING.get(nodeSettings)) {
                            nodeNames.add(Node.NODE_NAME_SETTING.get(nodeSettings));
                        }
                    }

                    for (Settings nodeSettings : allNodesSettings) {
                        if (Node.NODE_MASTER_SETTING.get(nodeSettings)) {
                            nodeNames.add(Node.NODE_NAME_SETTING.get(nodeSettings));
                        }
                    }

                    newSettings.add(Settings.builder().put(settings)
                            .putList(ClusterBootstrapService.INITIAL_MASTER_NODES_SETTING.getKey(), nodeNames)
                            .build());

                    setBootstrapMasterNodeIndex(-1);
                }
            }
        }

        return newSettings;
    }

    /**
     * Starts a node with default settings and returns its name.
     */
    public String startNode() {
        return startNode(Settings.EMPTY);
    }

    /**
     * Starts a node with the given settings builder and returns its name.
     */
    public String startNode(Settings.Builder settings) {
        return startNode(settings.build());
    }

    /**
     * Starts a node with the given settings and returns its name.
     */
    public String startNode(Settings settings) {
        return startNodes(settings).get(0);
    }

    /**
     * Starts multiple nodes with default settings and returns their names
     */
    public List<String> startNodes(int numOfNodes) {
        return startNodes(numOfNodes, Settings.EMPTY);
    }

    /**
     * Starts multiple nodes with the given settings and returns their names
     */
    public List<String> startNodes(int numOfNodes, Settings settings) {
        return startNodes(Collections.nCopies(numOfNodes, settings).toArray(new Settings[0]));
    }

    /**
     * Starts multiple nodes with the given settings and returns their names
     */
    public synchronized List<String> startNodes(Settings... extraSettings) {
        final int newMasterCount = Math.toIntExact(Stream.of(extraSettings).filter(Node.NODE_MASTER_SETTING::get).count());
        final List<NodeAndClient> nodes = new ArrayList<>();
        final int prevMasterCount = getMasterNodesCount();
        int autoBootstrapMasterNodeIndex = autoManageMasterNodes && prevMasterCount == 0 && newMasterCount > 0
            && Arrays.stream(extraSettings)
                    .allMatch(s -> Node.NODE_MASTER_SETTING.get(s) == false || ZEN2_DISCOVERY_TYPE.equals(DISCOVERY_TYPE_SETTING.get(s)))
            ? RandomNumbers.randomIntBetween(random, 0, newMasterCount - 1) : -1;

        final int numOfNodes = extraSettings.length;
        final int firstNodeId = nextNodeId.getAndIncrement();
        final List<Settings> settings = new ArrayList<>();
        for (int i = 0; i < numOfNodes; i++) {
            settings.add(getNodeSettings(firstNodeId + i, random.nextLong(), extraSettings[i]));
        }
        nextNodeId.set(firstNodeId + numOfNodes);

        final List<String> initialMasterNodes = settings.stream()
                .filter(Node.NODE_MASTER_SETTING::get)
                .map(Node.NODE_NAME_SETTING::get)
                .toList();

        final List<Settings> updatedSettings = bootstrapMasterNodeWithSpecifiedIndex(settings);

        for (int i = 0; i < numOfNodes; i++) {
            final Settings nodeSettings = updatedSettings.get(i);
            final Builder builder = Settings.builder();
            if (Node.NODE_MASTER_SETTING.get(nodeSettings)) {
                if (autoBootstrapMasterNodeIndex == 0) {
                    builder.putList(INITIAL_MASTER_NODES_SETTING.getKey(), initialMasterNodes);
                }
                autoBootstrapMasterNodeIndex -= 1;
            }

            final NodeAndClient nodeAndClient =
                    buildNode(firstNodeId + i, builder.put(nodeSettings).build(), false, () -> rebuildUnicastHostFiles(nodes));
            nodes.add(nodeAndClient);
        }
        startAndPublishNodesAndClients(nodes);
        if (autoManageMasterNodes) {
            validateClusterFormed();
        }
        return nodes.stream().map(NodeAndClient::getName).collect(Collectors.toList());
    }

    public List<String> startMasterOnlyNodes(int numNodes) {
        return startMasterOnlyNodes(numNodes, Settings.EMPTY);
    }

    public List<String> startMasterOnlyNodes(int numNodes, Settings settings) {
        Settings settings1 = Settings.builder()
                .put(settings)
                .put(Node.NODE_MASTER_SETTING.getKey(), true)
                .put(Node.NODE_DATA_SETTING.getKey(), false)
                .build();
        return startNodes(numNodes, settings1);
    }

    public List<String> startDataOnlyNodes(int numNodes) {
        return startDataOnlyNodes(numNodes, Settings.EMPTY);
    }

    public List<String> startDataOnlyNodes(int numNodes, Settings settings) {
        return startNodes(
            numNodes,
            Settings.builder().put(settings).put(Node.NODE_MASTER_SETTING.getKey(), false)
                .put(Node.NODE_DATA_SETTING.getKey(), true).build());
    }

    private int getMasterNodesCount() {
        return (int) nodes.values().stream().filter(n -> Node.NODE_MASTER_SETTING.get(n.node().settings())).count();
    }

    public String startMasterOnlyNode() {
        return startMasterOnlyNode(Settings.EMPTY);
    }

    public String startMasterOnlyNode(Settings settings) {
        Settings settings1 = Settings.builder()
                .put(settings)
                .put(Node.NODE_MASTER_SETTING.getKey(), true)
                .put(Node.NODE_DATA_SETTING.getKey(), false)
                .build();
        return startNode(settings1);
    }

    public String startDataOnlyNode() {
        return startDataOnlyNode(Settings.EMPTY);
    }

    public String startDataOnlyNode(Settings settings) {
        Settings settings1 = Settings.builder()
                .put(settings)
                .put(Node.NODE_MASTER_SETTING.getKey(), false)
                .put(Node.NODE_DATA_SETTING.getKey(), true)
                .build();
        return startNode(settings1);
    }

    private synchronized void publishNode(NodeAndClient nodeAndClient) {
        assert !nodeAndClient.node().isClosed();
        final NavigableMap<String, NodeAndClient> newNodes = new TreeMap<>(nodes);
        newNodes.put(nodeAndClient.name, nodeAndClient);
        nodes = Collections.unmodifiableNavigableMap(newNodes);
        applyDisruptionSchemeToNode(nodeAndClient);
    }

    public void closeNonSharedNodes(boolean wipeData) throws IOException {
        reset(wipeData);
    }

    /**
     * Returns the number of data nodes in the cluster.
     */
    public int numDataNodes() {
        return dataNodeAndClients().size();
    }

    /**
     * Returns the number of data and master eligible nodes in the cluster.
     */
    public int numDataAndMasterNodes() {
        return filterNodes(nodes, DATA_NODE_PREDICATE.or(MASTER_NODE_PREDICATE)).size();
    }

    public int numMasterNodes() {
      return filterNodes(nodes, NodeAndClient::isMasterEligible).size();
    }

    public void setDisruptionScheme(ServiceDisruptionScheme scheme) {
        assert activeDisruptionScheme == null :
            "there is already and active disruption [" + activeDisruptionScheme + "]. call clearDisruptionScheme first";
        scheme.applyToCluster(this);
        activeDisruptionScheme = scheme;
    }

    public void clearDisruptionScheme() {
        clearDisruptionScheme(true);
    }

    // synchronized to prevent concurrently modifying the cluster.
    public synchronized void clearDisruptionScheme(boolean ensureHealthyCluster) {
        if (activeDisruptionScheme != null) {
            TimeValue expectedHealingTime = activeDisruptionScheme.expectedTimeToHeal();
            logger.info("Clearing active scheme {}, expected healing time {}", activeDisruptionScheme, expectedHealingTime);
            if (ensureHealthyCluster) {
                activeDisruptionScheme.removeAndEnsureHealthy(this);
            } else {
                activeDisruptionScheme.removeFromCluster(this);
            }
        }
        activeDisruptionScheme = null;
    }

    private void applyDisruptionSchemeToNode(NodeAndClient nodeAndClient) {
        if (activeDisruptionScheme != null) {
            assert nodes.containsKey(nodeAndClient.name);
            activeDisruptionScheme.applyToNode(nodeAndClient.name, this);
        }
    }

    private void removeDisruptionSchemeFromNode(NodeAndClient nodeAndClient) {
        if (activeDisruptionScheme != null) {
            assert nodes.containsKey(nodeAndClient.name);
            activeDisruptionScheme.removeFromNode(nodeAndClient.name, this);
        }
    }

    private Collection<NodeAndClient> dataNodeAndClients() {
        return filterNodes(nodes, DATA_NODE_PREDICATE);
    }

    private static Collection<NodeAndClient> filterNodes(Map<String, TestCluster.NodeAndClient> map,
            Predicate<NodeAndClient> predicate) {
        return map
            .values()
            .stream()
            .filter(predicate)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static final class NodeNamePredicate implements Predicate<NodeAndClient> {
        private final String nodeName;

        NodeNamePredicate(String nodeName) {
            this.nodeName = nodeName;
        }

        @Override
        public boolean test(NodeAndClient nodeAndClient) {
            return nodeName.equals(nodeAndClient.getName());
        }
    }

    synchronized String routingKeyForShard(Index index, int shard, Random random) {
        assertThat(shard).isGreaterThanOrEqualTo(0);
        assertThat(shard).isGreaterThanOrEqualTo(0);
        for (NodeAndClient n : nodes.values()) {
            Node node = n.node;
            IndicesService indicesService = getInstanceFromNode(IndicesService.class, node);
            ClusterService clusterService = getInstanceFromNode(ClusterService.class, node);
            IndexService indexService = indicesService.indexService(index);
            if (indexService != null) {
                assertThat(indexService.getIndexSettings().getSettings().getAsInt(IndexMetadata.SETTING_NUMBER_OF_SHARDS, -1))
                    .isGreaterThan(shard);
                OperationRouting operationRouting = clusterService.operationRouting();
                while (true) {
                    String routing = RandomStrings.randomAsciiLettersOfLength(random, 10);
                    final int targetShard = operationRouting
                        .indexShards(clusterService.state(), index.getName(), null, routing)
                        .shardId()
                        .id();
                    if (shard == targetShard) {
                        return routing;
                    }
                }
            }
        }
        fail("Could not find a node that holds " + index);
        return null;
    }

    /**
     * Returns an {@link Iterable} over all clients in this test cluster
     */
    public Iterable<Client> getClients() {
        return () -> {
            ensureOpen();
            final Iterator<NodeAndClient> iterator = nodes.values().iterator();
            return new Iterator<Client>() {

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Client next() {
                    return iterator.next().client();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("");
                }

            };
        };
    }

    /**
     * Returns this clusters {@link NamedWriteableRegistry} this is needed to
     * deserialize binary content from this cluster that might include custom named writeables
     */
    public NamedWriteableRegistry getNamedWriteableRegistry() {
        return getInstance(NamedWriteableRegistry.class);
    }

    /**
     * Returns a predicate that only accepts settings of nodes with one of the given names.
     */
    public static Predicate<Settings> nameFilter(String... nodeNames) {
        final Set<String> nodes = Set.of(nodeNames);
        return settings -> nodes.contains(settings.get("node.name"));
    }

    /**
     * An abstract class that is called during {@link #rollingRestart(TestCluster.RestartCallback)}
     * and / or {@link #fullRestart(TestCluster.RestartCallback)} to execute actions at certain
     * stages of the restart.
     */
    public static class RestartCallback {

        /**
         * Executed once the give node name has been stopped.
         */
        public Settings onNodeStopped(String nodeName) throws Exception {
            return Settings.EMPTY;
        }

        /**
         * Executed for each node before the {@code n + 1} node is restarted. The given client is
         * an active client to the node that will be restarted next.
         */
        public void doAfterNodes(int n, Client client) throws Exception {
        }

        public void onAllNodesStopped() throws Exception {
        }

        /**
         * If this returns <code>true</code> all data for the node with the given node name will be cleared including
         * gateways and all index data. Returns <code>false</code> by default.
         */
        public boolean clearData(String nodeName) {
            return false;
        }

        /** returns true if the restart should also validate the cluster has reformed */
        public boolean validateClusterForming() { return true; }
    }

    public Settings getDefaultSettings() {
        return defaultSettings;
    }

    /**
     * This method checks all the things that need to be checked after each test
     */
    public synchronized void assertAfterTest() throws Exception {
        ensureEstimatedStats();
        assertRequestsFinished();
        assertNoInFlightDocsInEngine();
        for (NodeAndClient nodeAndClient : nodes.values()) {
            NodeEnvironment env = nodeAndClient.node().getNodeEnvironment();
            Set<ShardId> shardIds = env.lockedShards();
            for (ShardId id : shardIds) {
                try {
                    env.shardLock(id, "TestCluster assert after test", TimeUnit.SECONDS.toMillis(5)).close();
                } catch (ShardLockObtainFailedException ex) {
                    throw new AssertionError("Shard " + id + " is still locked after 5 sec waiting", ex);
                }
            }
        }
    }

    private void assertRequestsFinished() {
        assert Thread.holdsLock(this);
        for (NodeAndClient nodeAndClient : nodes.values()) {
            DiscoveryNode discoveryNode = getInstanceFromNode(ClusterService.class, nodeAndClient.node()).localNode();
            TasksService tasksService = getInstanceFromNode(TasksService.class, nodeAndClient.node());
            String pendingTasks = tasksService.getJobIdsByParticipatingNodes(discoveryNode.getId())
                .map(uuid -> tasksService.getTask(uuid).toString())
                .collect(Collectors.joining(",", "[", "]"));
            CircuitBreaker inFlightRequestsBreaker = getInstance(CircuitBreakerService.class, nodeAndClient.name)
                .getBreaker(CircuitBreaker.IN_FLIGHT_REQUESTS);

            TransportService transportService = getInstance(TransportService.class, nodeAndClient.name);
            try {
                // see #ensureEstimatedStats()
                assertBusy(() -> {
                    // ensure that our size accounting on transport level is reset properly
                    long bytesUsed = inFlightRequestsBreaker.getUsed();
                    String asMsg =
                        "All incoming requests on node [" + nodeAndClient.name + "] should have finished. "
                        + "in_flight_requests-bytesUsed=" + bytesUsed
                        + ", pendingTasks=" + pendingTasks
                        + ", responseHandlers=" + transportService.getResponseHandlers();
                    assertThat(bytesUsed)
                        .as(asMsg)
                        .isEqualTo(0L);
                }, 1, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.error("Could not assert finished requests within timeout", e);
                fail("Could not assert finished requests within timeout on node [" + nodeAndClient.name + "]");
            }
        }
    }

    public int numNodes() {
        return nodes.size();
    }

    public SQLTransportExecutor createSQLTransportExecutor() {
        return createSQLTransportExecutor(false, null);
    }

    public SQLTransportExecutor createSQLTransportExecutor(boolean useSSL, @Nullable String nodeName) {
        return new SQLTransportExecutor(
            new SQLTransportExecutor.ClientProvider() {

                @Override
                public Client client() {
                    if (nodeName == null) {
                        return TestCluster.this.client();
                    }
                    return TestCluster.this.client(nodeName);
                }

                @Override
                public String pgUrl() {
                    PostgresNetty postgresNetty = getInstance(PostgresNetty.class);
                    BoundTransportAddress boundAddress = postgresNetty.boundAddress();
                    if (boundAddress == null) {
                        return null;
                    }
                    InetSocketAddress address = boundAddress.publishAddress().address();
                    return String.format(
                        Locale.ENGLISH,
                        "jdbc:postgresql://%s:%d/?ssl=%s&sslmode=%s",
                        address.getHostName(),
                        address.getPort(),
                        useSSL,
                        useSSL ? "require" : "disable"
                    );
                }

                @Override
                public Sessions sessions() {
                    return getInstance(Sessions.class);
                }
            }
        );
    }

    public void dumpActiveTasks() {
        for (var tasksService : getInstances(TasksService.class)) {
            tasksService.logActiveTasksToError();
        }
    }
}

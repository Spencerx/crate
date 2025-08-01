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

package io.crate.session;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.UUIDs;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import io.crate.analyze.AnalyzedBegin;
import io.crate.analyze.AnalyzedClose;
import io.crate.analyze.AnalyzedCommit;
import io.crate.analyze.AnalyzedDeallocate;
import io.crate.analyze.AnalyzedDeclare;
import io.crate.analyze.AnalyzedDiscard;
import io.crate.analyze.AnalyzedStatement;
import io.crate.analyze.Analyzer;
import io.crate.analyze.ParamTypeHints;
import io.crate.analyze.ParameterTypes;
import io.crate.analyze.QueriedSelectRelation;
import io.crate.analyze.relations.AbstractTableRelation;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.auth.Protocol;
import io.crate.common.collections.Lists;
import io.crate.common.unit.TimeValue;
import io.crate.data.Row;
import io.crate.data.Row1;
import io.crate.data.RowConsumer;
import io.crate.data.RowN;
import io.crate.exceptions.JobKilledException;
import io.crate.exceptions.ReadOnlyException;
import io.crate.exceptions.SQLExceptions;
import io.crate.execution.dml.BulkResponse;
import io.crate.execution.engine.collect.stats.JobsLogs;
import io.crate.execution.jobs.kill.KillJobsNodeAction;
import io.crate.execution.jobs.kill.KillJobsNodeRequest;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.RelationInfo;
import io.crate.metadata.RoutingProvider;
import io.crate.metadata.settings.CoordinatorSessionSettings;
import io.crate.planner.DependencyCarrier;
import io.crate.planner.Plan;
import io.crate.planner.Planner;
import io.crate.planner.PlannerContext;
import io.crate.planner.operators.StatementClassifier;
import io.crate.planner.operators.SubQueryResults;
import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.protocols.postgres.FormatCodes;
import io.crate.protocols.postgres.JobsLogsUpdateListener;
import io.crate.protocols.postgres.Portal;
import io.crate.protocols.postgres.TransactionState;
import io.crate.protocols.postgres.parser.PgArrayParser;
import io.crate.sql.SqlFormatter;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.Declare;
import io.crate.sql.tree.Declare.Hold;
import io.crate.sql.tree.DiscardStatement.Target;
import io.crate.sql.tree.Statement;
import io.crate.types.DataType;

/**
 * Stateful Session
 * In the PSQL case there is one session per connection.
 * <p>
 * <p>
 * Methods are usually called in the following order:
 * <p>
 * <pre>
 * parse(...)
 * bind(...)
 * describe(...) // optional
 * execute(...)
 * sync()
 * </pre>
 * <p>
 * Or:
 * <p>
 * <pre>
 * parse(...)
 * loop:
 *      bind(...)
 *      execute(...)
 * sync()
 * </pre>
 * <p>
 * (https://www.postgresql.org/docs/9.2/static/protocol-flow.html#PROTOCOL-FLOW-EXT-QUERY)
 */
public class Session implements AutoCloseable {

    // Logger name should be SQLOperations here
    private static final Logger LOGGER = LogManager.getLogger(Sessions.class);

    // Parser can't handle empty statement but postgres requires support for it.
    // This rewrite is done so that bind/describe calls on an empty statement will work as well
    private static final Statement EMPTY_STMT = SqlParser.createStatement("select '' from sys.cluster limit 0");

    public static final String UNNAMED = "";
    private final DependencyCarrier executor;
    private final CoordinatorSessionSettings sessionSettings;

    @VisibleForTesting
    final Map<String, PreparedStmt> preparedStatements = new HashMap<>();
    @VisibleForTesting
    final Map<String, Portal> portals = new HashMap<>();

    final Cursors cursors = new Cursors();

    @VisibleForTesting
    final Map<Statement, List<DeferredExecution>> deferredExecutionsByStmt = new LinkedHashMap<>();

    @VisibleForTesting
    @Nullable
    CompletableFuture<?> activeExecution;
    @Nullable
    private UUID mostRecentJobID;

    private final int id;
    private final ConnectionProperties connectionProperties;
    private final long timeCreated;
    private final int secret;
    private final Analyzer analyzer;
    private final Planner planner;
    private final JobsLogs jobsLogs;
    private final boolean isReadOnly;
    private final Runnable onClose;
    private final int tempErrorRetryCount;
    private final int statementMaxLength;

    private TransactionState currentTransactionState = TransactionState.IDLE;
    private volatile String lastStmt;


    /**
     * @param connectionProperties client connection details. If null this is considered a system session
     **/
    public Session(int sessionId,
                   @Nullable ConnectionProperties connectionProperties,
                   Analyzer analyzer,
                   Planner planner,
                   JobsLogs jobsLogs,
                   boolean isReadOnly,
                   DependencyCarrier executor,
                   CoordinatorSessionSettings sessionSettings,
                   Runnable onClose,
                   int tempErrorRetryCount,
                   int statementMaxLength) {
        this.id = sessionId;
        this.connectionProperties = connectionProperties;
        this.timeCreated = System.currentTimeMillis();
        this.secret = ThreadLocalRandom.current().nextInt();
        this.analyzer = analyzer;
        this.planner = planner;
        this.jobsLogs = jobsLogs;
        this.isReadOnly = isReadOnly;
        this.executor = executor;
        this.sessionSettings = sessionSettings;
        this.onClose = onClose;
        this.tempErrorRetryCount = tempErrorRetryCount;
        this.statementMaxLength = statementMaxLength;
    }

    public int id() {
        return id;
    }

    public long timeCreated() {
        return timeCreated;
    }

    public boolean isSystemSession() {
        return connectionProperties == null;
    }

    public InetAddress clientAddress() {
        return connectionProperties == null ? null : connectionProperties.address();
    }

    public Protocol protocol() {
        return connectionProperties == null ? null : connectionProperties.protocol();
    }

    public boolean hasSSL() {
        return connectionProperties != null && connectionProperties.hasSSL();
    }

    public String lastStmt() {
        return lastStmt;
    }

    public int secret() {
        return secret;
    }

    public TimeoutToken newTimeoutToken() {
        return new TimeoutToken(sessionSettings.statementTimeout(), System.nanoTime());
    }

    /**
     * Execute a query in one step, avoiding the parse/bind/execute/sync procedure.
     * Opposed to using parse/bind/execute/sync this method is thread-safe.
     * This is used for system calls and statement_timeout is not accounted for here.
     */
    public void quickExec(String statement, ResultReceiver<?> resultReceiver, Row params) {
        lastStmt = statement;
        CoordinatorTxnCtx txnCtx = new CoordinatorTxnCtx(sessionSettings);
        validateStatementLength(statement);
        Statement parsedStmt = SqlParser.createStatement(statement);
        AnalyzedStatement analyzedStatement = analyzer.analyze(
            parsedStmt,
            sessionSettings,
            ParamTypeHints.EMPTY,
            cursors
        );
        RoutingProvider routingProvider = new RoutingProvider(Randomness.get().nextInt(), planner.getAwarenessAttributes());
        mostRecentJobID = UUIDs.dirtyUUID();
        final UUID jobId = mostRecentJobID;
        ClusterState clusterState = planner.currentClusterState();
        PlannerContext plannerContext = planner.createContext(
            routingProvider,
            jobId,
            txnCtx,
            0,
            params,
            cursors,
            currentTransactionState,
            TimeoutToken.noopToken()
        );
        Plan plan;
        try {
            plan = planner.plan(analyzedStatement, plannerContext);
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(jobId, statement, SQLExceptions.messageOf(t), sessionSettings.sessionUser());
            throw t;
        }

        StatementClassifier.Classification classification = StatementClassifier.classify(plan);
        jobsLogs.logExecutionStart(jobId, statement, sessionSettings.sessionUser(), classification);
        JobsLogsUpdateListener jobsLogsUpdateListener = new JobsLogsUpdateListener(jobId, jobsLogs);
        if (!analyzedStatement.isWriteOperation()) {
            resultReceiver = new RetryOnFailureResultReceiver<>(
                tempErrorRetryCount,
                executor.clusterService(),
                resultReceiver,
                jobId,
                (newJobId, retryResultReceiver) -> retryQuery(
                    newJobId,
                    analyzedStatement,
                    routingProvider,
                    new RowConsumerToResultReceiver(retryResultReceiver, 0, jobsLogsUpdateListener),
                    params,
                    txnCtx,
                    TimeoutToken.noopToken()
                )
            );
        }
        RowConsumerToResultReceiver consumer = new RowConsumerToResultReceiver(resultReceiver, 0, jobsLogsUpdateListener);
        plan.execute(executor, plannerContext, consumer, params, SubQueryResults.EMPTY);
    }

    private void retryQuery(UUID jobId,
                            AnalyzedStatement stmt,
                            RoutingProvider routingProvider,
                            RowConsumer consumer,
                            Row params,
                            CoordinatorTxnCtx txnCtx,
                            TimeoutToken timeoutToken) {
        PlannerContext plannerContext = planner.createContext(
            routingProvider,
            jobId,
            txnCtx,
            0,
            params,
            cursors,
            currentTransactionState,
            timeoutToken

        );
        Plan plan = planner.plan(stmt, plannerContext);
        if (timeoutToken != null) {
            timeoutToken.check();
        }
        plan.execute(executor, plannerContext, consumer, params, SubQueryResults.EMPTY);
    }

    private Portal getSafePortal(String portalName) {
        Portal portal = portals.get(portalName);
        if (portal == null) {
            throw new IllegalArgumentException("Cannot find portal: " + portalName);
        }
        return portal;
    }

    public CoordinatorSessionSettings sessionSettings() {
        return sessionSettings;
    }

    public void parse(String statementName, String query, List<DataType<?>> paramTypes) {
        TimeoutToken timeoutToken = newTimeoutToken();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("method=parse stmtName={} query={} paramTypes={}", statementName, query, paramTypes);
        }

        validateStatementLength(query);

        Statement statement;
        try {
            statement = SqlParser.createStatement(query);
        } catch (Throwable t) {
            if ("".equals(query)) {
                statement = EMPTY_STMT;
            } else {
                jobsLogs.logPreExecutionFailure(UUIDs.dirtyUUID(), query, SQLExceptions.messageOf(t), sessionSettings.sessionUser());
                throw t;
            }
        }
        timeoutToken.check();

        analyze(statementName, statement, paramTypes, query, timeoutToken);
    }

    private void validateStatementLength(String query) {
        if (query.length() > statementMaxLength) {
            String msg = String.format(
                Locale.ENGLISH,
                "Statement exceeds `statement_max_length` (%d allowed, %d provided). Try replacing inline values with parameter placeholders (`?`)",
                statementMaxLength,
                query.length()
            );
            jobsLogs.logPreExecutionFailure(UUIDs.dirtyUUID(), query, msg, sessionSettings.sessionUser());
            throw new IllegalArgumentException(msg);
        }
    }

    public void analyze(String statementName,
                        Statement statement,
                        List<DataType<?>> paramTypes,
                        @Nullable String query,
                        TimeoutToken timeoutToken) {
        AnalyzedStatement analyzedStatement;
        DataType<?>[] parameterTypes;
        try {
            analyzedStatement = analyzer.analyze(
                statement,
                sessionSettings,
                new ParamTypeHints(paramTypes),
                cursors
            );

            parameterTypes = ParameterTypes.extract(analyzedStatement).toArray(new DataType[0]);
            timeoutToken.check();
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(
                UUIDs.dirtyUUID(),
                query == null ? statementName : query,
                SQLExceptions.messageOf(t),
                sessionSettings.sessionUser());
            throw t;
        }

        preparedStatements.put(
            statementName,
            new PreparedStmt(statement, analyzedStatement, query, parameterTypes, timeoutToken));
    }

    public void bind(String portalName,
                     String statementName,
                     List<Object> params,
                     @Nullable FormatCodes.FormatCode[] resultFormatCodes) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("method=bind portalName={} statementName={} params={}", portalName, statementName, params);
        }
        PreparedStmt preparedStmt;
        try {
            preparedStmt = getSafeStmt(statementName);
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(UUIDs.dirtyUUID(), "", SQLExceptions.messageOf(t), sessionSettings.sessionUser());
            throw t;
        }

        Portal portal = new Portal(
            portalName,
            preparedStmt,
            params,
            resultFormatCodes);
        Portal oldPortal = portals.put(portalName, portal);
        if (oldPortal != null) {
            // According to the wire protocol spec named portals should be removed explicitly and only
            // unnamed portals are implicitly closed/overridden.
            // We don't comply with the spec because we allow batching of statements, see #execute
            oldPortal.closeActiveConsumer();
        }

        // Clients might try to describe a declared query, need to store a portal to allow that.
        if (preparedStmt.analyzedStatement() instanceof AnalyzedDeclare analyzedDeclare) {
            Declare declare = analyzedDeclare.declare();
            String cursorName = declare.cursorName();
            if (!cursorName.equals(portalName)) {
                var parameterTypes = ParameterTypes.extract(analyzedDeclare.query()).toArray(new DataType[0]);
                PreparedStmt preparedQuery = new PreparedStmt(
                    declare.query(),
                    analyzedDeclare.query(),
                    SqlFormatter.formatSql(declare.query()),
                    parameterTypes,
                    preparedStmt.timeoutToken()
                );
                Portal queryPortal = new Portal(
                    cursorName,
                    preparedQuery,
                    List.of(),
                    resultFormatCodes
                );
                portals.put(cursorName, queryPortal);
            }
        }
    }

    public DescribeResult describe(char type, String portalOrStatement) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("method=describe type={} portalOrStatement={}", type, portalOrStatement);
        }
        switch (type) {
            case 'P':
                Portal portal = getSafePortal(portalOrStatement);
                var analyzedStmt = portal.analyzedStatement();
                return new DescribeResult(
                    portal.preparedStmt().parameterTypes(),
                    analyzedStmt.outputs(),
                    analyzedStmt.outputNames(),
                    resolveTableFromSelect(analyzedStmt)
                );
            case 'S':
                /*
                 * describe might be called without prior bind call.
                 *
                 * If the client uses server-side prepared statements this is usually the case.
                 *
                 * E.g. the statement is first prepared:
                 *
                 *      parse stmtName=S_1 query=insert into t (x) values ($1) paramTypes=[integer]
                 *      describe type=S portalOrStatement=S_1
                 *      sync
                 *
                 * and then used with different bind calls:
                 *
                 *      bind portalName= statementName=S_1 params=[0]
                 *      describe type=P portalOrStatement=
                 *      execute
                 *
                 *      bind portalName= statementName=S_1 params=[1]
                 *      describe type=P portalOrStatement=
                 *      execute
                 */
                PreparedStmt preparedStmt = preparedStatements.get(portalOrStatement);
                AnalyzedStatement analyzedStatement = preparedStmt.analyzedStatement();
                return new DescribeResult(
                    preparedStmt.parameterTypes(),
                    analyzedStatement.outputs(),
                    analyzedStatement.outputNames(),
                    resolveTableFromSelect(analyzedStatement)
                );
            default:
                throw new AssertionError("Unsupported type: " + type);
        }
    }

    @Nullable
    private RelationInfo resolveTableFromSelect(AnalyzedStatement stmt) {
        // See description of {@link DescribeResult#relation()}
        // It is only populated if it is a SELECT on a single table
        if (stmt instanceof QueriedSelectRelation qsr) {
            List<AnalyzedRelation> from = qsr.from();
            if (from.size() == 1 && from.get(0) instanceof AbstractTableRelation<?> tableRelation) {
                return tableRelation.tableInfo();
            }
        }
        return null;
    }

    @Nullable
    public CompletableFuture<?> execute(String portalName, int maxRows, ResultReceiver<?> resultReceiver) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("method=execute portalName={} maxRows={}", portalName, maxRows);
        }
        Portal portal = getSafePortal(portalName);
        var activeConsumer = portal.activeConsumer();
        if (activeConsumer != null && activeConsumer.suspended()) {
            activeConsumer.replaceResultReceiver(resultReceiver, maxRows);
            activeConsumer.resume();
            return resultReceiver.completionFuture();
        }

        var analyzedStmt = portal.analyzedStatement();
        if (isReadOnly && analyzedStmt.isWriteOperation()) {
            throw new ReadOnlyException(portal.preparedStmt().rawStatement());
        }
        if (analyzedStmt instanceof AnalyzedBegin) {
            currentTransactionState = TransactionState.IN_TRANSACTION;
            resultReceiver.allFinished();
        } else if (analyzedStmt instanceof AnalyzedCommit) {
            currentTransactionState = TransactionState.IDLE;
            cursors.close(cursor -> cursor.hold() == Hold.WITHOUT);
            resultReceiver.allFinished();
            return resultReceiver.completionFuture();
        } else if (analyzedStmt instanceof AnalyzedDeallocate ad) {
            String stmtToDeallocate = ad.preparedStmtName();
            if (stmtToDeallocate != null) {
                close((byte) 'S', stmtToDeallocate);
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("deallocating all prepared statements");
                }
                preparedStatements.clear();
            }
            resultReceiver.allFinished();
        } else if (analyzedStmt instanceof AnalyzedDiscard discard) {
            // We don't cache plans, don't have sequences or temporary tables
            // See https://www.postgresql.org/docs/current/sql-discard.html
            if (discard.target() == Target.ALL) {
                close();
            }
            resultReceiver.allFinished();
        } else if (analyzedStmt.isWriteOperation()) {
            /* We defer the execution for any other statements to `sync` messages so that we can efficiently process
             * bulk operations. E.g. If we receive `INSERT INTO (x) VALUES (?)` bindings/execute multiple times
             * We want to create bulk requests internally:                                                          /
             * - To reduce network overhead
             * - To have 1 disk flush per shard instead of 1 disk flush per item
             *
             * Many clients support this by doing something like this:
             *
             *      var preparedStatement = conn.prepareStatement("...")
             *      for (var args in manyArgs):
             *          preparedStatement.execute(args)
             *      conn.commit()
             */
            deferredExecutionsByStmt.compute(
                portal.preparedStmt().parsedStatement(), (_, oldValue) -> {
                    DeferredExecution deferredExecution = new DeferredExecution(portal, maxRows, resultReceiver);
                    if (oldValue == null) {
                        ArrayList<DeferredExecution> deferredExecutions = new ArrayList<>();
                        deferredExecutions.add(deferredExecution);
                        return deferredExecutions;
                    } else {
                        oldValue.add(deferredExecution);
                        return oldValue;
                    }
                }
            );
            return resultReceiver.completionFuture();
        } else {
            if (analyzedStmt instanceof AnalyzedClose close) {
                String cursorName = close.cursorName();
                if (cursorName != null) {
                    Portal removedPortal = portals.remove(cursorName);
                    if (removedPortal != null) {
                        removedPortal.closeActiveConsumer();
                    }
                }
            }
            if (!deferredExecutionsByStmt.isEmpty()) {
                throw new UnsupportedOperationException(
                    "Only write operations are allowed in Batch statements");
            }
            if (activeExecution == null) {
                activeExecution = singleExec(portal, resultReceiver, maxRows);
            } else {
                activeExecution = activeExecution
                    .thenCompose(_ -> singleExec(portal, resultReceiver, maxRows));
            }
            return activeExecution;
        }
        return null;
    }

    public void flush() {
        assert !deferredExecutionsByStmt.isEmpty()
            : "Session.flush() must only be called if there are deferred executions";

        // This will make `sync` use the triggered execution instead of
        // returning a new completed future.
        //
        // We need to wait for the operation that is now being triggered to complete
        // before any further messages are sent to clients.
        // E.g. PostgresWireProtocol would otherwise send a `ReadyForQuery` message too
        // early.
        activeExecution = triggerDeferredExecutions(false);
    }

    public CompletableFuture<?> sync(boolean forceBulk) {
        if (activeExecution == null) {
            return triggerDeferredExecutions(forceBulk);
        } else {
            LOGGER.debug("method=sync activeExecution={}", activeExecution);
            var result = activeExecution;
            activeExecution = null;
            return result;
        }
    }

    public List<Statement> simpleQuery(String queryString) {
        try {
            return SqlParser.createStatementsForSimpleQuery(
                queryString,
                str -> PgArrayParser.parse(
                    str,
                    bytes -> new String(bytes, StandardCharsets.UTF_8)
                )
            );
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(UUIDs.dirtyUUID(), queryString, SQLExceptions.messageOf(t), sessionSettings.sessionUser());
            throw t;
        }
    }

    private void addStatementTimeout(CompletableFuture<?> result, TimeoutToken timeoutToken) {
        long durationNs = timeoutToken.disable();
        TimeValue timeout = sessionSettings.statementTimeout();
        final UUID jobId = mostRecentJobID;
        long timeoutNanos = timeout.nanos();
        if (jobId == null || timeoutNanos <= 0) {
            return;
        }
        long remainingTimeoutMs = (long) ((timeoutNanos - durationNs) * 0.000001);
        assert remainingTimeoutMs > 0 : "Execute must have positive timeout if timeout was not exceeded on previous phases.";
        Runnable kill = () -> {
            if (result.isDone()) {
                return;
            }
            KillJobsNodeRequest request = new KillJobsNodeRequest(
                List.of(),
                List.of(jobId),
                sessionSettings.userName(),
                "statement_timeout (" + timeout.toString() + ")"
            );
            executor.client().execute(KillJobsNodeAction.INSTANCE, request);
        };
        ScheduledExecutorService scheduler = executor.scheduler();
        ScheduledFuture<?> schedule = scheduler.schedule(kill, remainingTimeoutMs, TimeUnit.MILLISECONDS);
        result.whenComplete((_, _) -> schedule.cancel(false));
    }

    private CompletableFuture<?> triggerDeferredExecutions(boolean forceBulk) {
        int numDeferred = deferredExecutionsByStmt.size();
        LOGGER.debug("method=sync deferredExecutions={}", numDeferred);
        switch (numDeferred) {
            case 0:
                return CompletableFuture.completedFuture(null);
            case 1: {
                var deferredExecutions = deferredExecutionsByStmt.values().iterator().next();
                deferredExecutionsByStmt.clear();
                return exec(deferredExecutions, forceBulk);
            }
            default: {
                // Mix of different deferred execution is PG specific.
                // HTTP sync-s at the end of both single/bulk requests, and it's always one statement.
                // sequentiallize execution to ensure client receives row counts in correct order
                CompletableFuture<?> allCompleted = null;
                for (var entry : deferredExecutionsByStmt.entrySet()) {
                    var deferredExecutions = entry.getValue();
                    if (allCompleted == null) {
                        allCompleted = exec(deferredExecutions, forceBulk);
                    } else {
                        allCompleted = allCompleted
                            // individual rowReceiver will receive failure; must not break execution chain due to failures.
                            // No need to log execution and as it's handled in the exec() call.
                            .exceptionally(_ -> null)
                            .thenCompose(_ -> exec(deferredExecutions, forceBulk));
                    }
                }
                deferredExecutionsByStmt.clear();
                return allCompleted;
            }
        }
    }

    private CompletableFuture<?> exec(List<DeferredExecution> executions, boolean forceBulk) {
        if (executions.size() == 1 && !forceBulk) {
            var toExec = executions.get(0);
            return singleExec(toExec.portal(), toExec.resultReceiver(), toExec.maxRows());
        } else {
            return bulkExec(executions);
        }
    }

    private CompletableFuture<?> bulkExec(List<DeferredExecution> toExec) {
        assert !toExec.isEmpty() : "Must have at least 1 deferred execution for bulk exec";
        mostRecentJobID = UUIDs.dirtyUUID();
        final UUID jobId = mostRecentJobID;
        var routingProvider = new RoutingProvider(Randomness.get().nextInt(), planner.getAwarenessAttributes());
        var txnCtx = new CoordinatorTxnCtx(sessionSettings);
        PreparedStmt firstPreparedStatement = toExec.get(0).portal().preparedStmt();
        TimeoutToken timeoutToken = firstPreparedStatement.timeoutToken();
        timeoutToken.enable();

        var plannerContext = planner.createContext(
            routingProvider,
            jobId,
            txnCtx,
            0,
            null,
            cursors,
            currentTransactionState,
            timeoutToken
        );


        AnalyzedStatement analyzedStatement = firstPreparedStatement.analyzedStatement();
        lastStmt = firstPreparedStatement.rawStatement();

        Plan plan;
        try {
            plan = planner.plan(analyzedStatement, plannerContext);
            timeoutToken.check();
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(
                jobId,
                firstPreparedStatement.rawStatement(),
                SQLExceptions.messageOf(t),
                sessionSettings.sessionUser());
            throw t;
        }
        jobsLogs.logExecutionStart(
            jobId,
            firstPreparedStatement.rawStatement(),
            sessionSettings.sessionUser(),
            StatementClassifier.classify(plan)
        );

        var bulkArgs = Lists.map(toExec, x -> (Row) new RowN(x.portal().params().toArray()));
        CompletableFuture<BulkResponse> result = plan.executeBulk(
            executor,
            plannerContext,
            bulkArgs,
            SubQueryResults.EMPTY
        );
        List<CompletableFuture<?>> resultReceiverFutures = Lists.map(toExec, x -> x.resultReceiver().completionFuture());
        CompletableFuture<Void> allResultReceivers = CompletableFuture.allOf(resultReceiverFutures.toArray(new CompletableFuture[0]));

        result
            .thenAccept(bulkResp -> emitRowCountsToResultReceivers(jobId, jobsLogs, toExec, bulkResp))
            .exceptionally(t -> {
                for (int i = 0; i < toExec.size(); i++) {
                    toExec.get(i).resultReceiver().fail(t);
                }
                jobsLogs.logExecutionEnd(jobId, SQLExceptions.messageOf(t));
                return null;
            });
        addStatementTimeout(result, timeoutToken);
        return result.runAfterBoth(allResultReceivers, () -> {});

    }

    private static void emitRowCountsToResultReceivers(UUID jobId,
                                                       JobsLogs jobsLogs,
                                                       List<DeferredExecution> executions,
                                                       BulkResponse bulkResponse) {
        Object[] cells = new Object[2];
        RowN row = new RowN(cells);
        for (int i = 0; i < bulkResponse.size(); i++) {
            ResultReceiver<?> resultReceiver = executions.get(i).resultReceiver();
            try {
                cells[0] = bulkResponse.rowCount(i);
                cells[1] = bulkResponse.failure(i);
            } catch (Throwable t) {
                cells[0] = Row1.ERROR;
                cells[1] = t;
            }
            try {
                resultReceiver.setNextRow(row);
            } catch (Exception e) {
                // Ignore
            } finally {
                resultReceiver.allFinished();
            }
        }
        jobsLogs.logExecutionEnd(jobId, null);
    }

    @VisibleForTesting
    CompletableFuture<?> singleExec(Portal portal, ResultReceiver<?> resultReceiver, int maxRows) {
        RowConsumerToResultReceiver activeConsumer = portal.activeConsumer();
        if (activeConsumer != null) {
            activeConsumer.closeAndFinishIfSuspended();
            return activeConsumer.completionFuture();
        }

        mostRecentJobID = UUIDs.dirtyUUID();
        final UUID jobId = mostRecentJobID;
        var routingProvider = new RoutingProvider(Randomness.get().nextInt(), planner.getAwarenessAttributes());
        var txnCtx = new CoordinatorTxnCtx(sessionSettings);
        var params = new RowN(portal.params().toArray());
        TimeoutToken timeoutToken = portal.preparedStmt().timeoutToken();
        timeoutToken.enable();
        var plannerContext = planner.createContext(
            routingProvider,
            jobId,
            txnCtx,
            maxRows,
            params,
            cursors,
            currentTransactionState,
            timeoutToken
        );
        var analyzedStmt = portal.analyzedStatement();

        String rawStatement = portal.preparedStmt().rawStatement();
        lastStmt = rawStatement;
        if (analyzedStmt == null) {
            String errorMsg = "Statement must have been analyzed: " + rawStatement;
            jobsLogs.logPreExecutionFailure(jobId, rawStatement, errorMsg, sessionSettings.sessionUser());
            throw new IllegalStateException(errorMsg);
        }
        Plan plan;
        try {
            plan = planner.plan(analyzedStmt, plannerContext);
            timeoutToken.check();
        } catch (Throwable t) {
            jobsLogs.logPreExecutionFailure(jobId, rawStatement, SQLExceptions.messageOf(t), sessionSettings.sessionUser());
            throw t;
        }
        if (!analyzedStmt.isWriteOperation()) {
            resultReceiver = new RetryOnFailureResultReceiver<>(
                tempErrorRetryCount,
                executor.clusterService(),
                resultReceiver,
                jobId,
                (newJobId, resultRec) -> retryQuery(
                    newJobId,
                    analyzedStmt,
                    routingProvider,
                    new RowConsumerToResultReceiver(
                        resultRec,
                        maxRows,
                        new JobsLogsUpdateListener(newJobId, jobsLogs)),
                    params,
                    txnCtx,
                    timeoutToken
                )
            );
        }
        jobsLogs.logExecutionStart(
            jobId, rawStatement, sessionSettings.sessionUser(), StatementClassifier.classify(plan));
        RowConsumerToResultReceiver consumer = new RowConsumerToResultReceiver(
            resultReceiver, maxRows, new JobsLogsUpdateListener(jobId, jobsLogs));
        portal.setActiveConsumer(consumer);
        plan.execute(executor, plannerContext, consumer, params, SubQueryResults.EMPTY);
        CompletableFuture<?> result = resultReceiver.completionFuture();
        addStatementTimeout(result, timeoutToken);
        return result;
    }

    @Nullable
    public List<? extends DataType<?>> getOutputTypes(String portalName) {
        Portal portal = getSafePortal(portalName);
        var analyzedStatement = portal.analyzedStatement();
        List<Symbol> fields = analyzedStatement.outputs();
        if (fields != null) {
            return Symbols.typeView(fields);
        }
        return null;
    }

    public String getQuery(String portalName) {
        return getSafePortal(portalName).preparedStmt().rawStatement();
    }

    public DataType<?> getParamType(String statementName, int idx) {
        PreparedStmt stmt = getSafeStmt(statementName);
        return stmt.getEffectiveParameterType(idx);
    }

    private PreparedStmt getSafeStmt(String statementName) {
        PreparedStmt preparedStmt = preparedStatements.get(statementName);
        if (preparedStmt == null) {
            throw new IllegalArgumentException("No statement found with name: " + statementName);
        }
        return preparedStmt;
    }

    @Nullable
    public FormatCodes.FormatCode[] getResultFormatCodes(String portal) {
        return getSafePortal(portal).resultFormatCodes();
    }

    /**
     * Close a portal or prepared statement
     *
     * <p>
     *     From PostgreSQL ExtendedQuery protocol spec:
     * </p>
     *
     * <p>
     *     The Close message closes an existing prepared statement or portal and releases resources.
     *     It is not an error to issue Close against a nonexistent statement or portal name.
     *     [..]
     *     Note that closing a prepared statement implicitly closes any open portals that were constructed from that statement.
     * </p>
     *
     * @param type <b>S</b> for prepared statement, <b>P</b> for portal.
     * @param name name of the prepared statement or the portal (depending on type)
     */
    public void close(byte type, String name) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("method=close type={} name={}", (char) type, name);
        }

        switch (type) {
            case 'P': {
                Portal portal = portals.remove(name);
                if (portal != null) {
                    portal.closeActiveConsumer();
                }
                return;
            }
            case 'S': {
                PreparedStmt preparedStmt = preparedStatements.remove(name);
                if (preparedStmt != null) {
                    Iterator<Map.Entry<String, Portal>> it = portals.entrySet().iterator();
                    while (it.hasNext()) {
                        var entry = it.next();
                        var portal = entry.getValue();
                        if (portal.preparedStmt().equals(preparedStmt)) {
                            portal.closeActiveConsumer();
                            it.remove();
                        }
                    }
                }
                return;
            }
            default:
                throw new IllegalArgumentException("Invalid type: " + type + ", valid types are: [P, S]");
        }
    }

    @Override
    public void close() {
        currentTransactionState = TransactionState.IDLE;
        resetDeferredExecutions();
        activeExecution = null;
        for (Portal portal : portals.values()) {
            portal.closeActiveConsumer();
        }
        portals.clear();
        preparedStatements.clear();
        cursors.close(_ -> true);
        onClose.run();
    }

    public boolean hasDeferredExecutions() {
        return !deferredExecutionsByStmt.isEmpty();
    }

    public void resetDeferredExecutions() {
        for (var deferredExecutions : deferredExecutionsByStmt.values()) {
            for (DeferredExecution deferredExecution : deferredExecutions) {
                deferredExecution.portal().closeActiveConsumer();
                portals.remove(deferredExecution.portal().name());
            }
        }
        deferredExecutionsByStmt.clear();
    }

    public TransactionState transactionState() {
        return currentTransactionState;
    }

    @Nullable
    public java.util.UUID getMostRecentJobID() {
        return mostRecentJobID;
    }

    public void cancelCurrentJob() {
        if (mostRecentJobID == null) {
            return;
        }
        var request = new KillJobsNodeRequest(
            List.of(),
            List.of(mostRecentJobID),
            sessionSettings.userName(),
            "Cancellation request by: " + sessionSettings.userName()
        );
        executor.client().execute(KillJobsNodeAction.INSTANCE, request);
        resetDeferredExecutions();
    }

    @Override
    public String toString() {
        return "Session{" +
            ", mostRecentJobID=" + mostRecentJobID +
            ", id=" + id +
            "}";
    }

    /**
     * Controls execution time of all lifecycle phases of a statement (parse/analysis/plan/execution).
     * If statement_timeout is specified, statement is stopped when processing exceeds it.
     */
    public static class TimeoutToken {

        protected TimeValue statementTimeout;
        private long startNanos;
        private boolean enabled = true;

        public TimeoutToken(TimeValue statementTimeout, long startNanos) {
            this.statementTimeout = statementTimeout;
            this.startNanos = startNanos;
        }


        public static TimeoutToken noopToken() {
            return new TimeoutToken();
        }

        private TimeoutToken() {
            this.enabled = false;
        }

        public void check() {
            if (enabled && statementTimeout.nanos() > 0) {
                long durationNs = System.nanoTime() - startNanos;
                if (durationNs > statementTimeout.nanos()) {
                    throw JobKilledException.of("statement_timeout (" + statementTimeout + ")");
                }
            }
        }

        /**
         * Disables the token.
         * This should be called on `execute` to prevent the token from running into timeouts
         * if it is reused when a prepared statement/portal is executed again.
         **/
        public long disable() {
            enabled = false;
            return System.nanoTime() - startNanos;
        }

        /**
         * Re-enable a disabled token; noop if already enabled
         **/
        public void enable() {
            if (!enabled) {
                startNanos = System.nanoTime();
                enabled = true;
            }
        }
    }
}

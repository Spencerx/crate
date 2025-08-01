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

package io.crate.testing;

import static io.crate.session.Session.UNNAMED;
import static io.crate.types.ResultSetParser.getObject;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.test.ESTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import com.carrotsearch.randomizedtesting.RandomizedContext;

import io.crate.auth.AccessControl;
import io.crate.auth.Protocol;
import io.crate.common.exceptions.Exceptions;
import io.crate.common.unit.TimeValue;
import io.crate.concurrent.FutureActionListener;
import io.crate.data.Row;
import io.crate.exceptions.SQLExceptions;
import io.crate.execution.dml.BulkResponse;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.SearchPath;
import io.crate.metadata.pgcatalog.PgCatalogSchemaInfo;
import io.crate.planner.optimizer.LoadedRules;
import io.crate.planner.optimizer.Rule;
import io.crate.protocols.postgres.ConnectionProperties;
import io.crate.protocols.postgres.types.PGType;
import io.crate.protocols.postgres.types.PGTypes;
import io.crate.role.Role;
import io.crate.session.BaseResultReceiver;
import io.crate.session.DescribeResult;
import io.crate.session.ResultReceiver;
import io.crate.session.Session;
import io.crate.session.Sessions;
import io.crate.types.ArrayType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.JsonType;

public class SQLTransportExecutor {

    private static final String SQL_REQUEST_TIMEOUT = "CRATE_TESTS_SQL_REQUEST_TIMEOUT";

    public static final TimeValue REQUEST_TIMEOUT = new TimeValue(Long.parseLong(
        Objects.requireNonNullElse(System.getenv(SQL_REQUEST_TIMEOUT), "10")), TimeUnit.SECONDS);

    private static final Logger LOGGER = LogManager.getLogger(SQLTransportExecutor.class);

    private static final TestExecutionConfig EXECUTION_FEATURES_DISABLED = new TestExecutionConfig(false, false, false, 0, List.of());

    private final ClientProvider clientProvider;

    private SearchPath searchPath = SearchPath.pathWithPGCatalogAndDoc();

    public SQLTransportExecutor(ClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    public String getCurrentSchema() {
        return searchPath.currentSchema();
    }

    public void setSearchPath(String... searchPath) {
        this.searchPath = SearchPath.createSearchPathFrom(searchPath);
    }

    public SQLResponse exec(String statement) {
        return executeTransportOrJdbc(EXECUTION_FEATURES_DISABLED, statement, null, REQUEST_TIMEOUT);
    }

    public SQLResponse exec(TestExecutionConfig config, String statement, Object[] params) {
        return executeTransportOrJdbc(config, statement, params, REQUEST_TIMEOUT);
    }

    public SQLResponse exec(TestExecutionConfig config, String statement, Object[] params, TimeValue timeout) {
        return executeTransportOrJdbc(config, statement, params, timeout);
    }

    public SQLResponse exec(String statement, Object[] params) {
        return executeTransportOrJdbc(EXECUTION_FEATURES_DISABLED, statement, params, REQUEST_TIMEOUT);
    }

    public BulkResponse execBulk(String statement, @Nullable Object[][] bulkArgs) {
        return executeBulk(statement, bulkArgs, REQUEST_TIMEOUT, null);
    }

    public BulkResponse execBulk(String statement, @Nullable Object[][] bulkArgs, TimeValue timeout) {
        return executeBulk(statement, bulkArgs, timeout, null);
    }

    public BulkResponse execBulk(String statement, @Nullable Object[][] bulkArgs, Session session) {
        return executeBulk(statement, bulkArgs, REQUEST_TIMEOUT, session);
    }

    @VisibleForTesting
    static List<String> buildRandomizedRuleSessionSettings(Random random,
                                                           double percentageOfRulesToDisable,
                                                           List<Class<? extends Rule<?>>> allRules,
                                                           List<Class<? extends Rule<?>>> rulesToKeep) {
        assert percentageOfRulesToDisable > 0 && percentageOfRulesToDisable <= 1 :
            "Percentage of rules to disable for Rule Randomization must greater than 0 and equal or less than 1";

        var ruleToKeepNames = new HashSet<>(rulesToKeep);

        var ruleCandidates = new ArrayList<Class<? extends Rule<?>>>();
        for (var rule : allRules) {
            if (ruleToKeepNames.contains(rule) == false) {
                ruleCandidates.add(rule);
            }
        }

        Collections.shuffle(ruleCandidates, random);
        int numberOfRulesToPick = (int) Math.ceil(ruleCandidates.size() * percentageOfRulesToDisable);

        var result = new ArrayList<String>(numberOfRulesToPick);
        for (int i = 0; i < numberOfRulesToPick; i++) {
            result.add(String.format(Locale.ENGLISH,
                                     "set %s=false",
                                     Rule.sessionSettingName(ruleCandidates.get(i))));
        }

        return result;
    }

    private SQLResponse executeTransportOrJdbc(TestExecutionConfig config,
                                               String stmt,
                                               @Nullable Object[] args,
                                               TimeValue timeout) {
        final String pgUrl = clientProvider.pgUrl();
        Random random = RandomizedContext.current().getRandom();

        List<String> sessionList = new ArrayList<>();
        sessionList.add("set search_path to "
                        + StreamSupport.stream(searchPath.spliterator(), false)
                            // explicitly setting the pg catalog schema will make it the current schema so attempts to
                            // create un-fully-qualified relations will fail. we filter it out and will implicitly
                            // remain the first in the search path.
                            .filter(s -> !s.equals(PgCatalogSchemaInfo.NAME))
                            .collect(Collectors.joining(", "))
        );

        if (!config.isHashJoinEnabled()) {
            sessionList.add("set enable_hashjoin=false");
            LOGGER.trace("Executing with enable_hashjoin=false: {}", stmt);
        }

        if (config.isRuleRandomizationEnabled()) {
            sessionList.addAll(buildRandomizedRuleSessionSettings(
                random,
                config.amountOfRulesToDisable(),
                LoadedRules.INSTANCE.rules(),
                config.rulesToKeep()));
        }

        if (pgUrl != null && config.isJdbcEnabled()) {
            LOGGER.trace("Executing with pgJDBC: {}", stmt);
            return executeWithPg(
                stmt,
                args,
                pgUrl,
                random,
                sessionList,
                timeout);
        }
        try {
            try (Session session = newSession()) {
                session.sessionSettings().statementTimeout(timeout);
                sessionList.forEach(setting -> exec(setting, session));
                return FutureUtils.get(execute(stmt, args, session), timeout.millis(), TimeUnit.MILLISECONDS);
            }
        } catch (ElasticsearchTimeoutException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            throw new ElasticsearchTimeoutException("Timeout while running `" + stmt + "`", cause);
        } catch (Throwable t) {
            // ActionListener.onFailure takes `Exception` as argument instead of `Throwable`.
            // That requires us to wrap Throwable; That Throwable may be an AssertionError.
            //
            // Wrapping the exception can hide parts of the stacktrace that are interesting
            // to figure out the root cause of an error, so we prefer the cause here
            Exceptions.rethrowUnchecked(SQLExceptions.unwrap(t));

            // unreachable
            return null;
        }
    }

    public String jdbcUrl() {
        return clientProvider.pgUrl();
    }

    public CompletableFuture<SQLResponse> execute(String stmt, @Nullable Object[] args) {
        Session session = newSession();
        CompletableFuture<SQLResponse> result = execute(stmt, args, session);
        return result.whenComplete((_, _) -> session.close());
    }

    public Session newSession() {
        return clientProvider.sessions().newSession(
            new ConnectionProperties(null, null, Protocol.HTTP, null),
            searchPath.currentSchema(),
            Role.CRATE_USER
        );
    }

    public SQLResponse executeAs(String stmt, Role user) {
        try (Session session = clientProvider.sessions()
            .newSession(new ConnectionProperties(null, null, Protocol.HTTP, null), null, user)) {
            return FutureUtils.get(execute(stmt, null, session), SQLTransportExecutor.REQUEST_TIMEOUT.millis(), TimeUnit.MILLISECONDS);
        }
    }

    public SQLResponse exec(String statement, @Nullable Object[] args, Session session, TimeValue timeout) {
        return FutureUtils.get(execute(statement, args, session), timeout.millis(), TimeUnit.MILLISECONDS);
    }

    public SQLResponse exec(String statement, @Nullable Object[] args, Session session) {
        return exec(statement, args, session, REQUEST_TIMEOUT);
    }

    public SQLResponse exec(String statement, Session session) {
        return exec(statement, null, session);
    }

    public static CompletableFuture<SQLResponse> execute(String stmt,
                                                         @Nullable Object[] args,
                                                         Session session) {
        FutureActionListener<SQLResponse> future = new FutureActionListener<>();
        execute(stmt, args, future, session);
        return future.exceptionally(err -> {
            Exceptions.rethrowUnchecked(SQLExceptions.prepareForClientTransmission(AccessControl.DISABLED, err));
            return null;
        });
    }

    private static void execute(String stmt,
                                @Nullable Object[] args,
                                ActionListener<SQLResponse> listener,
                                @Nullable Session session) {
        try {
            session.parse(UNNAMED, stmt, Collections.emptyList());
            List<Object> argsList = args == null ? Collections.emptyList() : Arrays.asList(args);
            session.bind(UNNAMED, UNNAMED, argsList, null);
            DescribeResult describeResult = session.describe('P', UNNAMED);
            if (describeResult.getFields() == null) {
                ResultReceiver<?> resultReceiver = new RowCountReceiver(listener);
                session.execute(UNNAMED, 0, resultReceiver);
            } else {
                ResultReceiver<?> resultReceiver = new ResultSetReceiver(
                    listener, describeResult.getFields(), describeResult.getFieldNames());
                session.execute(UNNAMED, 0, resultReceiver);
            }
            session.sync(false);
        } catch (Throwable t) {
            listener.onFailure(Exceptions.toException(t));
        }
    }

    private void executeBulk(String stmt,
                             @Nullable Object[][] bulkArgs,
                             final ActionListener<BulkResponse> listener,
                             @Nullable Session s) {
        if (bulkArgs != null && bulkArgs.length == 0) {
            listener.onResponse(new BulkResponse(0));
            return;
        }

        if (s == null) {
            s = newSession();
        }
        final Session session = s; // Final for lambda.
        try {
            session.parse(UNNAMED, stmt, Collections.emptyList());
            var bulkResponse = new BulkResponse(bulkArgs == null ? 0 : bulkArgs.length);
            if (bulkArgs == null) {
                session.bind(UNNAMED, UNNAMED, Collections.emptyList(), null);
                session.execute(UNNAMED, 0, new BaseResultReceiver());
            } else {
                for (int i = 0; i < bulkArgs.length; i++) {
                    session.bind(UNNAMED, UNNAMED, Arrays.asList(bulkArgs[i]), null);
                    ResultReceiver<?> resultReceiver = new BulkRowCountReceiver(bulkResponse, i);
                    session.execute(UNNAMED, 0, resultReceiver);
                }
            }
            List<Symbol> outputColumns = session.describe('P', UNNAMED).getFields();
            if (outputColumns != null) {
                throw new UnsupportedOperationException(
                    "Bulk operations for statements that return result sets is not supported");
            }
            session.sync(false).whenComplete((Object _, Throwable t) -> {
                if (t == null) {
                    listener.onResponse(bulkResponse);
                } else {
                    listener.onFailure(Exceptions.toException(t));
                }
                session.close();
            });
        } catch (Throwable t) {
            session.close();
            listener.onFailure(Exceptions.toException(t));
        }
    }

    private SQLResponse executeWithPg(String stmt,
                                      @Nullable Object[] args,
                                      String pgUrl,
                                      Random random,
                                      List<String> setSessionStatementsList,
                                      TimeValue timeout) {
        try {
            Properties properties = new Properties();
            if (random.nextBoolean()) {
                properties.setProperty("prepareThreshold", "-1"); // always use prepared statements
            }
            properties.put("option", "-c statement_timeout=" + timeout.millis());
            properties.put("user", Role.CRATE_USER.name());
            try (Connection conn = DriverManager.getConnection(pgUrl, properties)) {
                conn.setAutoCommit(true);
                for (String setSessionStmt : setSessionStatementsList) {
                    conn.createStatement().execute(setSessionStmt);
                }
                try (PreparedStatement preparedStatement = conn.prepareStatement(stmt)) {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            preparedStatement.setObject(i + 1, toJdbcCompatObject(conn, args[i]));
                        }
                    }
                    return executeAndConvertResult(preparedStatement);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Error executing stmt={} args={} error={}", stmt, Arrays.toString(args), e);
            Exceptions.rethrowUnchecked(e);
            // this should never happen
            return null;
        }
    }

    public static Object toJdbcCompatObject(Connection connection, Object arg) {
        if (arg == null) {
            return arg;
        }
        if (arg instanceof Map) {
            return DataTypes.STRING.implicitCast(arg);
        }
        if (arg instanceof Object[] values) {
            arg = Arrays.asList(values);
        }
        if (arg instanceof Collection<?> values) {
            if (values.isEmpty()) {
                return null; // Can't insert empty list without knowing the type
            }
            List<Object> convertedValues = new ArrayList<>(values.size());
            PGType<?> pgType = null;
            for (Object value : values) {
                convertedValues.add(toJdbcCompatObject(connection, value));
                if (pgType == null && value != null) {
                    pgType = PGTypes.get(DataTypes.guessType(value));
                }
            }
            try {
                return connection.createArrayOf(pgType.typName(), convertedValues.toArray(new Object[0]));
            } catch (SQLException e) {
                /*
                 * pg error message doesn't include a stacktrace.
                 * Set a breakpoint in {@link io.crate.protocols.postgres.Messages#sendErrorResponse(Channel, Throwable)}
                 * to inspect the error
                 */
                throw new RuntimeException(e);
            }
        }
        return arg;
    }

    private static SQLResponse executeAndConvertResult(PreparedStatement preparedStatement) throws SQLException {
        if (preparedStatement.execute()) {
            ResultSetMetaData metadata = preparedStatement.getMetaData();
            ResultSet resultSet = preparedStatement.getResultSet();
            List<Object[]> rows = new ArrayList<>();
            List<String> columnNames = new ArrayList<>(metadata.getColumnCount());
            DataType<?>[] dataTypes = new DataType[metadata.getColumnCount()];
            for (int i = 0; i < metadata.getColumnCount(); i++) {
                columnNames.add(metadata.getColumnName(i + 1));
                String columnTypeName = metadata.getColumnTypeName(i + 1);
                dataTypes[i] = columnTypeName.startsWith("_")
                    ? new ArrayType<>(getDataType(columnTypeName.substring(1)))
                    : getDataType(columnTypeName);
            }
            while (resultSet.next()) {
                Object[] row = new Object[metadata.getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    Object value;
                    String typeName = metadata.getColumnTypeName(i + 1);
                    value = getObject(resultSet, i, typeName);
                    if (typeName.equals("char") && value != null) {
                        // ResultSetParser is dedicated to deal with Postgres specific "char" type which can be single ASCII character.
                        // This code is also used in tests to get results via JDBC which is not necessarily bound to PG logic.
                        // Hence, cancel PG specific logic here.
                        assert value instanceof String;
                        value = Byte.parseByte((String) value);
                    }
                    row[i] = value;
                }
                rows.add(row);
            }
            return new SQLResponse(
                columnNames.toArray(new String[0]),
                rows.toArray(new Object[0][]),
                dataTypes,
                rows.size()
            );
        } else {
            int updateCount = preparedStatement.getUpdateCount();
            if (updateCount < 0) {
                /*
                 * In Crate -1 means row-count unknown, and -2 means error. In JDBC -2 means row-count unknown and -3 means error.
                 * See {@link java.sql.Statement#EXECUTE_FAILED}
                 */
                updateCount += 1;
            }
            return new SQLResponse(
                new String[0],
                new Object[0][],
                new DataType[0],
                updateCount
            );
        }
    }

    /**
     * Map type name from jdbc response (metadata) to a DataType
     * This roughly follows {@link PGTypes}
     */
    private static DataType<?> getDataType(String pgTypeName) {
        return switch (pgTypeName) {
            case "int2" -> DataTypes.SHORT;
            case "int4" -> DataTypes.INTEGER;
            case "int8" -> DataTypes.LONG;
            case "json" -> JsonType.INSTANCE;
            default -> DataTypes.UNDEFINED;
        };
    }

    /**
     * @return an array with the rowCounts
     */
    private BulkResponse executeBulk(String stmt, Object[][] bulkArgs, TimeValue timeout, @Nullable Session session) {
        try {
            FutureActionListener<BulkResponse> listener = new FutureActionListener<>();
            executeBulk(stmt, bulkArgs, listener, session);
            var future = listener.exceptionally(err -> {
                Exceptions.rethrowUnchecked(SQLExceptions.prepareForClientTransmission(AccessControl.DISABLED, err));
                return null;
            });
            return FutureUtils.get(future, timeout);
        } catch (ElasticsearchTimeoutException e) {
            LOGGER.error("Timeout on SQL statement: " + stmt, e);
            throw e;
        }
    }

    public void ensureGreen() throws Exception {
        ensureState(ClusterHealthStatus.GREEN, null);
    }

    public void ensureGreen(Integer expectedNumNodes) throws Exception {
        ensureState(ClusterHealthStatus.GREEN, expectedNumNodes);
    }

    public void ensureYellowOrGreen(Integer expectedNumNOdes) throws Exception {
        ensureState(ClusterHealthStatus.YELLOW, expectedNumNOdes);
    }

    public void ensureYellowOrGreen() throws Exception {
        ensureState(ClusterHealthStatus.YELLOW, null);
    }

    @SuppressWarnings("unchecked")
    private void ensureState(ClusterHealthStatus state, @Nullable Integer expectedNumNodes) throws Exception {
        assertThat(state)
            .as("ensureState can only be used to check for GREEN or YELLOW state")
            .isNotEqualTo(ClusterHealthStatus.RED);
        ESTestCase.assertBusy(() -> {
            SQLResponse response;
            try {
                response = exec(
                    """
                        select
                            (select count(*) from sys.nodes) as num_nodes,
                            (select
                                {
                                    health = health,
                                    pending_tasks = pending_tasks
                                } as cluster_health
                                FROM sys.cluster_health
                            ),
                            (select
                                {
                                    all_states = array_unique(array_agg(current_state)),
                                    primary_states = array_unique(array_agg(current_state) FILTER (WHERE primary = true)),
                                    relocating = count(*) FILTER (WHERE current_state = 'RELOCATING')
                                } as shards_state
                                FROM sys.allocations
                            )
                        """
                );
            } catch (Exception e) {
                // retry, don't trip assertBusy early.
                // There can be recoverable exceptions:
                // master not yet discovered, timeout, CoordinationStateRejectedException e.t.c
                throw new AssertionError(e);
            }
            if (expectedNumNodes != null) {
                assertThat(response.rows()[0][0]).isEqualTo((long) expectedNumNodes);
            }
            Map<String, Object> clusterHealth = (Map<String, Object>) response.rows()[0][1];
            Map<String, Object> shardsState = (Map<String, Object>) response.rows()[0][2];

            long pendingTasks = ((Number) clusterHealth.get("pending_tasks")).longValue();
            assertThat(pendingTasks).isEqualTo(0);
            long numRelocating = ((Number) shardsState.get("relocating")).longValue();
            assertThat(numRelocating).isEqualTo(0);

            String color = (String) clusterHealth.get("health");
            List<String> statesToCheck;
            if (state == ClusterHealthStatus.GREEN) {
                assertThat(color).isEqualTo("GREEN");
                statesToCheck = (List<String>) shardsState.get("all_states");
            } else {
                assertThat(color).satisfiesAnyOf(
                    x -> assertThat(x).isEqualTo("GREEN"),
                    x -> assertThat(x).isEqualTo("YELLOW")
                );
                statesToCheck = (List<String>) shardsState.get("primary_states");
            }
            assertThat(statesToCheck).satisfiesAnyOf(
                x -> assertThat(x).isEmpty(),
                x -> assertThat(x).isEqualTo(List.of("STARTED"))
            );
        });
    }

    public interface ClientProvider {
        Client client();

        @Nullable
        String pgUrl();

        Sessions sessions();
    }


    private static final DataType<?>[] EMPTY_TYPES = new DataType[0];
    private static final String[] EMPTY_NAMES = new String[0];
    private static final Object[][] EMPTY_ROWS = new Object[0][];

    /**
     * Wrapper for testing issues. Creates a {@link SQLResponse} from
     * query results.
     */
    private static class ResultSetReceiver extends BaseResultReceiver {

        private final List<Object[]> rows = new ArrayList<>();
        private final ActionListener<SQLResponse> listener;
        private final List<Symbol> outputFields;
        private final List<String> outputFieldNames;

        ResultSetReceiver(ActionListener<SQLResponse> listener, List<Symbol> outputFields, List<String> outputFieldNames) {
            this.listener = listener;
            this.outputFields = outputFields;
            this.outputFieldNames = outputFieldNames;
        }

        @Override
        @Nullable
        public CompletableFuture<Void> setNextRow(Row row) {
            rows.add(row.materialize());
            return null;
        }

        @Override
        public void allFinished() {
            try {
                SQLResponse response = createSqlResponse();
                listener.onResponse(response);
            } catch (Exception e) {
                listener.onFailure(e);
            }
            super.allFinished();
        }

        @Override
        public void fail(@NotNull Throwable t) {
            listener.onFailure(Exceptions.toException(t));
            super.fail(t);
        }

        private SQLResponse createSqlResponse() {
            String[] outputNames = new String[outputFields.size()];
            DataType<?>[] outputTypes = new DataType[outputFields.size()];

            for (int i = 0, outputFieldsSize = outputFields.size(); i < outputFieldsSize; i++) {
                outputNames[i] = outputFieldNames.get(i);
                outputTypes[i] = outputFields.get(i).valueType();
            }

            Object[][] rowsArr = rows.toArray(new Object[0][]);
            return new SQLResponse(
                outputNames,
                rowsArr,
                outputTypes,
                rowsArr.length
            );
        }
    }

    /**
     * Wrapper for testing issues. Creates a {@link SQLResponse} with
     * rowCount and duration of query execution.
     */
    private static class RowCountReceiver extends BaseResultReceiver {

        private final ActionListener<SQLResponse> listener;

        private long rowCount;

        RowCountReceiver(ActionListener<SQLResponse> listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<Void> setNextRow(Row row) {
            rowCount = (long) row.get(0);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void allFinished() {
            SQLResponse sqlResponse = new SQLResponse(
                EMPTY_NAMES,
                EMPTY_ROWS,
                EMPTY_TYPES,
                rowCount
            );
            listener.onResponse(sqlResponse);
            super.allFinished();

        }

        @Override
        public void fail(@NotNull Throwable t) {
            listener.onFailure(Exceptions.toException(t));
            super.fail(t);
        }
    }


    /**
     * Wraps results of bulk requests for testing.
     */
    private static class BulkRowCountReceiver extends BaseResultReceiver {

        private final int resultIdx;
        private final BulkResponse bulkResponse;

        BulkRowCountReceiver(BulkResponse bulkResponse, int resultIdx) {
            this.bulkResponse = bulkResponse;
            this.resultIdx = resultIdx;
        }

        @Override
        @Nullable
        public CompletableFuture<Void> setNextRow(Row row) {
            long rowCount = (long) row.get(0);
            Throwable failure = null;
            // Can be an optimized bulk request with only 1 bulk arg/operation which carries only 1 column (row count).
            if (bulkResponse.size() > 1) {
                failure = (Throwable) row.get(1);
            }
            bulkResponse.update(resultIdx, rowCount, failure);
            return null;
        }

        @Override
        public void allFinished() {
            super.allFinished();
        }

        @Override
        public void fail(@NotNull Throwable t) {
            super.fail(t);
        }
    }
}

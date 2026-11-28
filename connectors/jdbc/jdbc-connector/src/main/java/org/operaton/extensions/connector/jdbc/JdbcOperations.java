/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.connector.jdbc.internal.NamedSql;
import org.operaton.extensions.connector.jdbc.internal.ParameterBinder;
import org.operaton.extensions.connector.jdbc.internal.ResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes named-parameter SQL against a {@link JdbcDataSourceRegistry}. It is the common core of the connectors and
 * the delegates and can be used directly from Java.
 *
 * <p>Every value is bound through a {@link PreparedStatement}; no value is ever concatenated into the SQL text. Each
 * call gets its own connection (or the engine's connection when the data source participates in the engine
 * transaction), so instances are immutable and thread-safe.
 */
public final class JdbcOperations {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOperations.class);

    /**
     * A query.
     *
     * @param datasource data source name; {@code null} means {@value JdbcDataSourceRegistry#DEFAULT_NAME}
     * @param sql statement with named parameters
     * @param parameters values by parameter name
     * @param parameterTypes optional explicit types by parameter name
     * @param timeoutSeconds statement timeout; {@code null} uses the data source default, {@code 0} none
     * @param maxRows row limit; {@code null} uses the data source default
     * @param truncate {@code true}: return the first {@code maxRows} rows when there are more; {@code false}: fail
     * @param columnCase how column labels become map keys
     */
    public record Query(
            String datasource,
            String sql,
            Map<String, Object> parameters,
            Map<String, SqlTypeHint> parameterTypes,
            Integer timeoutSeconds,
            Integer maxRows,
            boolean truncate,
            ColumnCase columnCase) {

        public Query(String datasource, String sql, Map<String, Object> parameters) {
            this(datasource, sql, parameters, Map.of(), null, null, false, ColumnCase.AS_IS);
        }
    }

    /** Rows of a query; {@code truncated} is only ever {@code true} when truncation was requested. */
    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {}

    /**
     * An update (INSERT, UPDATE, DELETE, DDL).
     *
     * @param returnGeneratedKeys also return the keys the database generated
     * @param generatedKeyColumns columns to return as generated keys (implies {@code returnGeneratedKeys}); empty lets
     *     the driver decide
     */
    public record Update(
            String datasource,
            String sql,
            Map<String, Object> parameters,
            Map<String, SqlTypeHint> parameterTypes,
            Integer timeoutSeconds,
            boolean returnGeneratedKeys,
            List<String> generatedKeyColumns,
            ColumnCase columnCase) {

        public Update(String datasource, String sql, Map<String, Object> parameters) {
            this(datasource, sql, parameters, Map.of(), null, false, List.of(), ColumnCase.AS_IS);
        }
    }

    public record UpdateResult(int updateCount, List<Map<String, Object>> generatedKeys) {}

    private final JdbcDataSourceRegistry registry;

    public JdbcOperations(JdbcDataSourceRegistry registry) {
        this.registry = registry;
    }

    /** Operations on the data sources of the engine the current command runs in, see {@link JdbcConnectors}. */
    public static JdbcOperations current() {
        return new JdbcOperations(JdbcConnectors.current());
    }

    public QueryResult query(Query query) {
        String name = dataSourceName(query.datasource());
        NamedSql.Bound bound = bind(query.sql(), query.parameters());
        JdbcDataSourceProperties settings = registry.settings(name);
        int limit = query.maxRows() != null ? query.maxRows() : settings.getDefaultMaxRows();
        if (limit < 1) {
            throw new JdbcConnectorException("maxRows must be at least 1, was " + limit);
        }
        LOG.debug("jdbc query on data source '{}': {}", name, bound.sql());
        try (ConnectionHandle handle = registry.open(name);
                PreparedStatement ps = handle.connection().prepareStatement(bound.sql())) {
            prepare(ps, bound, query.parameterTypes(), timeout(query.timeoutSeconds(), settings));
            ps.setMaxRows(limit == Integer.MAX_VALUE ? limit : limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = ResultMapper.labels(md, query.columnCase());
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == limit) {
                        if (!query.truncate()) {
                            throw new JdbcConnectorException("Query on data source '" + name + "' returned more than "
                                    + limit + " rows; narrow the query, raise maxRows or set truncate=true");
                        }
                        truncated = true;
                        break;
                    }
                    rows.add(ResultMapper.row(rs, md, columns));
                }
                return new QueryResult(columns, rows, truncated);
            }
        } catch (SQLException e) {
            throw sqlFailure("query", name, e);
        }
    }

    public UpdateResult update(Update update) {
        String name = dataSourceName(update.datasource());
        NamedSql.Bound bound = bind(update.sql(), update.parameters());
        JdbcDataSourceProperties settings = registry.settings(name);
        List<String> keyColumns = update.generatedKeyColumns() == null ? List.of() : update.generatedKeyColumns();
        boolean keys = update.returnGeneratedKeys() || !keyColumns.isEmpty();
        LOG.debug("jdbc update on data source '{}': {}", name, bound.sql());
        try (ConnectionHandle handle = registry.open(name);
                PreparedStatement ps = prepareUpdate(handle, bound.sql(), keys, keyColumns)) {
            prepare(ps, bound, update.parameterTypes(), timeout(update.timeoutSeconds(), settings));
            int count = ps.executeUpdate();
            List<Map<String, Object>> generated = new ArrayList<>();
            if (keys) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs != null) {
                        ResultSetMetaData md = rs.getMetaData();
                        List<String> columns = ResultMapper.labels(md, update.columnCase());
                        while (rs.next()) {
                            generated.add(ResultMapper.row(rs, md, columns));
                        }
                    }
                }
            }
            return new UpdateResult(count, generated);
        } catch (SQLException e) {
            throw sqlFailure("update", name, e);
        }
    }

    private static PreparedStatement prepareUpdate(
            ConnectionHandle handle, String sql, boolean keys, List<String> columns) throws SQLException {
        if (!keys) {
            return handle.connection().prepareStatement(sql);
        }
        if (columns.isEmpty()) {
            return handle.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        }
        return handle.connection().prepareStatement(sql, columns.toArray(String[]::new));
    }

    private static String dataSourceName(String datasource) {
        return datasource == null || datasource.isBlank() ? JdbcDataSourceRegistry.DEFAULT_NAME : datasource.trim();
    }

    private static NamedSql.Bound bind(String sql, Map<String, Object> parameters) {
        return NamedSql.parse(sql).bind(parameters == null ? Map.of() : parameters);
    }

    private static int timeout(Integer requested, JdbcDataSourceProperties settings) {
        if (requested != null) {
            if (requested < 0) {
                throw new JdbcConnectorException("timeout must not be negative, was " + requested);
            }
            return requested;
        }
        long millis = settings.getDefaultQueryTimeout().toMillis();
        return (int) Math.min(Integer.MAX_VALUE, (millis + 999) / 1000);
    }

    private static void prepare(
            PreparedStatement ps, NamedSql.Bound bound, Map<String, SqlTypeHint> types, int timeoutSeconds)
            throws SQLException {
        ps.setQueryTimeout(timeoutSeconds);
        for (int i = 0; i < bound.values().size(); i++) {
            String parameter = bound.names().get(i);
            ParameterBinder.bind(
                    ps, i + 1, parameter, bound.values().get(i), types == null ? null : types.get(parameter));
        }
    }

    private static JdbcConnectorException sqlFailure(String what, String dataSource, SQLException e) {
        return new JdbcConnectorException(
                "JDBC " + what + " on data source '" + dataSource + "' failed (SQLState " + e.getSQLState()
                        + ", vendor code " + e.getErrorCode() + "): " + e.getMessage(),
                e);
    }
}

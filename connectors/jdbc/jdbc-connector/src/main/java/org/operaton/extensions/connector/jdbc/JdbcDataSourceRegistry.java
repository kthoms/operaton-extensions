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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The named data sources the connector can use. Built from {@link JdbcDataSourceProperties} (HikariCP pool from a URL,
 * a JNDI lookup) and from {@code DataSource} instances passed in (for example Spring beans).
 *
 * <p>Thread-safe: after construction the registry is read-only; connections are obtained per call. {@link #close()}
 * closes the pools this registry created; data sources that were passed in or looked up are not closed.
 */
public final class JdbcDataSourceRegistry implements AutoCloseable {

    /** Name used when a call does not select a data source. */
    public static final String DEFAULT_NAME = "default";

    private static final Logger LOG = LoggerFactory.getLogger(JdbcDataSourceRegistry.class);

    private record Entry(
            String name, DataSource dataSource, JdbcDataSourceProperties settings, HikariDataSource ownedPool) {}

    private final Map<String, Entry> entries;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param properties settings by data source name
     * @param instances ready data sources by name; an instance named like a properties entry is used for that entry
     *     (the entry then only supplies the statement defaults)
     * @throws JdbcConnectorException if a data source is misconfigured or cannot be resolved
     */
    public JdbcDataSourceRegistry(Map<String, JdbcDataSourceProperties> properties, Map<String, DataSource> instances) {
        Map<String, Entry> built = new LinkedHashMap<>();
        List<HikariDataSource> created = new ArrayList<>();
        try {
            for (Map.Entry<String, JdbcDataSourceProperties> e : properties.entrySet()) {
                String name = e.getKey();
                JdbcDataSourceProperties settings = e.getValue();
                settings.validate(name);
                DataSource supplied = instances.get(name);
                if (supplied != null) {
                    built.put(name, new Entry(name, supplied, settings, null));
                } else if (JdbcDataSourceProperties.isSet(settings.getUrl())) {
                    HikariDataSource pool = createPool(name, settings);
                    created.add(pool);
                    built.put(name, new Entry(name, pool, settings, pool));
                } else if (JdbcDataSourceProperties.isSet(settings.getJndiName())) {
                    built.put(name, new Entry(name, lookupJndi(name, settings.getJndiName()), settings, null));
                } else if (JdbcDataSourceProperties.isSet(settings.getBeanName())) {
                    throw new JdbcConnectorException("Data source '" + name + "' refers to bean '"
                            + settings.getBeanName() + "' but no DataSource instance was supplied for it"
                            + " (bean-name is resolved by the Spring Boot starter)");
                } else {
                    throw new JdbcConnectorException(
                            "Data source '" + name + "' needs one of url, jndi-name or bean-name");
                }
            }
            for (Map.Entry<String, DataSource> e : instances.entrySet()) {
                built.computeIfAbsent(e.getKey(), name -> new Entry(name, e.getValue(), defaults(name), null));
            }
        } catch (RuntimeException ex) {
            created.forEach(HikariDataSource::close);
            throw ex;
        }
        this.entries = Collections.unmodifiableMap(built);
        LOG.info("JDBC connector data sources: {}", entries.keySet());
    }

    private static JdbcDataSourceProperties defaults(String name) {
        JdbcDataSourceProperties settings = new JdbcDataSourceProperties();
        settings.validate(name);
        return settings;
    }

    private static HikariDataSource createPool(String name, JdbcDataSourceProperties s) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("operaton-jdbc-" + name);
        config.setJdbcUrl(s.getUrl());
        if (JdbcDataSourceProperties.isSet(s.getUsername())) {
            config.setUsername(s.getUsername());
        }
        if (s.getPassword() != null) {
            config.setPassword(s.getPassword());
        }
        if (JdbcDataSourceProperties.isSet(s.getDriverClassName())) {
            config.setDriverClassName(s.getDriverClassName());
        }
        config.setMaximumPoolSize(s.getMaximumPoolSize());
        if (s.getMinimumIdle() != null) {
            config.setMinimumIdle(s.getMinimumIdle());
        }
        config.setConnectionTimeout(s.getConnectionTimeout().toMillis());
        config.setIdleTimeout(s.getIdleTimeout().toMillis());
        config.setMaxLifetime(s.getMaxLifetime().toMillis());
        config.setValidationTimeout(s.getValidationTimeout().toMillis());
        config.setInitializationFailTimeout(s.getInitializationFailTimeoutMillis());
        config.setReadOnly(s.isReadOnly());
        config.setAutoCommit(true);
        if (JdbcDataSourceProperties.isSet(s.getSchema())) {
            config.setSchema(s.getSchema());
        }
        s.getDriverProperties().forEach(config::addDataSourceProperty);
        try {
            return new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new JdbcConnectorException(
                    "Data source '" + name + "': cannot create the connection pool for " + s.getUrl() + ": "
                            + e.getMessage(),
                    e);
        }
    }

    private static DataSource lookupJndi(String name, String jndiName) {
        try {
            Object found = new InitialContext().lookup(jndiName);
            if (found instanceof DataSource ds) {
                return ds;
            }
            throw new JdbcConnectorException("Data source '" + name + "': JNDI name '" + jndiName
                    + "' is not a javax.sql.DataSource but "
                    + (found == null ? "null" : found.getClass().getName()));
        } catch (NamingException e) {
            throw new JdbcConnectorException(
                    "Data source '" + name + "': JNDI lookup of '" + jndiName + "' failed: " + e.getMessage(), e);
        }
    }

    public Set<String> names() {
        return entries.keySet();
    }

    /** The settings of a data source (statement defaults, participation flag). */
    public JdbcDataSourceProperties settings(String name) {
        return entry(name).settings();
    }

    private Entry entry(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new JdbcConnectorException(
                    "Unknown data source '" + name + "'; configured data sources: " + entries.keySet());
        }
        return entry;
    }

    /**
     * Obtains a connection for one statement: a pooled connection in auto-commit mode (default), or the engine's own
     * connection when the data source has {@code participateInEngineTransaction}.
     */
    public ConnectionHandle open(String name) {
        if (closed.get()) {
            throw new JdbcConnectorException("The JDBC data source registry is closed");
        }
        Entry entry = entry(name);
        try {
            if (entry.settings().isParticipateInEngineTransaction()) {
                return new ConnectionHandle(engineConnection(name), true, entry.settings());
            }
            return new ConnectionHandle(entry.dataSource().getConnection(), false, entry.settings());
        } catch (SQLException e) {
            throw new JdbcConnectorException(
                    "Cannot obtain a connection from data source '" + name + "': " + e.getMessage(), e);
        }
    }

    private static Connection engineConnection(String name) throws SQLException {
        CommandContext commandContext =
                Context.getCommandInvocationContext() == null ? null : Context.getCommandContext();
        if (commandContext == null) {
            throw new JdbcConnectorException(
                    "Data source '" + name
                            + "' participates in the engine transaction, but there is no engine command running on this thread");
        }
        Connection connection = commandContext.getDbSqlSession().getSqlSession().getConnection();
        // The engine only rolls its MyBatis session back when the session itself wrote something, and closing the
        // session switches the connection back to auto-commit, which would commit our statements. Roll back explicitly.
        commandContext.getTransactionContext().addTransactionListener(TransactionState.ROLLINGBACK, context -> {
            try {
                connection.rollback();
            } catch (SQLException e) {
                throw new JdbcConnectorException(
                        "Cannot roll back the JDBC work of data source '" + name + "': " + e.getMessage(), e);
            }
        });
        return connection;
    }

    /**
     * Checks that every data source with {@code participateInEngineTransaction} is the engine's data source.
     *
     * @throws JdbcConnectorException if one is not
     */
    public void verifyEngineParticipation(ProcessEngineConfigurationImpl engineConfiguration) {
        for (Entry entry : entries.values()) {
            if (!entry.settings().isParticipateInEngineTransaction()) {
                continue;
            }
            DataSource engineDataSource = engineConfiguration.getDataSource();
            if (engineDataSource == null || !sameDataSource(entry.dataSource(), engineDataSource)) {
                throw new JdbcConnectorException("Data source '" + entry.name()
                        + "' has participate-in-engine-transaction=true but is not the engine's data source;"
                        + " only the DataSource the engine itself uses can share its transaction");
            }
        }
    }

    static boolean sameDataSource(DataSource a, DataSource b) {
        return a == b || unwrapsTo(a, b) || unwrapsTo(b, a);
    }

    private static boolean unwrapsTo(DataSource wrapper, DataSource target) {
        try {
            return wrapper.isWrapperFor(target.getClass()) && wrapper.unwrap(target.getClass()) == target;
        } catch (SQLException | RuntimeException e) {
            LOG.debug(
                    "DataSource {} cannot be unwrapped to {}",
                    wrapper.getClass().getName(),
                    target.getClass(),
                    e);
            return false;
        }
    }

    /** Closes the pools this registry created. Idempotent. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            entries.values().stream()
                    .map(Entry::ownedPool)
                    .filter(p -> p != null)
                    .forEach(HikariDataSource::close);
        }
    }
}

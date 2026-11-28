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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of one named data source. Exactly one of {@link #setUrl url} (the connector creates a HikariCP pool),
 * {@link #setJndiName jndiName} (looked up in JNDI) or {@link #setBeanName beanName} (a Spring {@code DataSource} bean,
 * resolved by the Spring Boot starter) selects where connections come from. Programmatic users can instead pass a
 * {@code DataSource} instance to the registry.
 *
 * <p>This is a plain mutable bean so that Spring Boot, XML configuration and code can all populate it; it is not
 * modified after the registry has been created.
 */
public class JdbcDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private String driverClassName;
    private String jndiName;
    private String beanName;
    private Map<String, String> driverProperties = new LinkedHashMap<>();

    private int maximumPoolSize = 10;
    private Integer minimumIdle;
    private Duration connectionTimeout = Duration.ofSeconds(30);
    private Duration idleTimeout = Duration.ofMinutes(10);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private Duration validationTimeout = Duration.ofSeconds(5);
    private long initializationFailTimeoutMillis = -1;
    private boolean readOnly;
    private String schema;

    private Duration defaultQueryTimeout = Duration.ofSeconds(30);
    private int defaultMaxRows = 1000;
    private boolean participateInEngineTransaction;

    /** JDBC URL; makes the connector create and own a HikariCP pool. */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** Driver class; optional for drivers registered through the JDBC {@code ServiceLoader}. */
    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    /** JNDI name of a {@code DataSource} (for example {@code java:comp/env/jdbc/business}). */
    public String getJndiName() {
        return jndiName;
    }

    public void setJndiName(String jndiName) {
        this.jndiName = jndiName;
    }

    /** Name of a Spring {@code DataSource} bean; only the Spring Boot starter resolves it. */
    public String getBeanName() {
        return beanName;
    }

    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    /** Extra driver properties passed to the pool ({@code dataSourceProperties}). */
    public Map<String, String> getDriverProperties() {
        return driverProperties;
    }

    public void setDriverProperties(Map<String, String> driverProperties) {
        this.driverProperties = driverProperties == null ? new LinkedHashMap<>() : driverProperties;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    /** Minimum idle connections; {@code null} keeps the pool at {@code maximumPoolSize} (HikariCP default). */
    public Integer getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(Integer minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public Duration getValidationTimeout() {
        return validationTimeout;
    }

    public void setValidationTimeout(Duration validationTimeout) {
        this.validationTimeout = validationTimeout;
    }

    /**
     * Milliseconds the pool tries to connect when it starts. Negative (default): the pool starts without connecting, so
     * an unavailable database does not prevent the engine from starting; a call then fails with a connection error.
     * Positive: fail at startup if no connection can be made in that time.
     */
    public long getInitializationFailTimeoutMillis() {
        return initializationFailTimeoutMillis;
    }

    public void setInitializationFailTimeoutMillis(long initializationFailTimeoutMillis) {
        this.initializationFailTimeoutMillis = initializationFailTimeoutMillis;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    /** Statement timeout used when a call does not set {@code timeout}; zero means no timeout. */
    public Duration getDefaultQueryTimeout() {
        return defaultQueryTimeout;
    }

    public void setDefaultQueryTimeout(Duration defaultQueryTimeout) {
        this.defaultQueryTimeout = defaultQueryTimeout;
    }

    /** Row limit of a query when a call does not set {@code maxRows}. */
    public int getDefaultMaxRows() {
        return defaultMaxRows;
    }

    public void setDefaultMaxRows(int defaultMaxRows) {
        this.defaultMaxRows = defaultMaxRows;
    }

    /**
     * Run the connector's SQL on the engine's own JDBC connection, in the engine's transaction. Only valid when this
     * data source is the engine's data source; checked at startup. Default {@code false}: own connection, auto-commit.
     */
    public boolean isParticipateInEngineTransaction() {
        return participateInEngineTransaction;
    }

    public void setParticipateInEngineTransaction(boolean participateInEngineTransaction) {
        this.participateInEngineTransaction = participateInEngineTransaction;
    }

    /** Validates the settings; the data source source (url, jndi, bean, instance) is checked by the registry. */
    void validate(String name) {
        int sources = (isSet(url) ? 1 : 0) + (isSet(jndiName) ? 1 : 0) + (isSet(beanName) ? 1 : 0);
        if (sources > 1) {
            throw new JdbcConnectorException(
                    "Data source '" + name + "': set only one of url, jndi-name and bean-name");
        }
        if (maximumPoolSize < 1) {
            throw new JdbcConnectorException("Data source '" + name + "': maximum-pool-size must be at least 1");
        }
        if (defaultMaxRows < 1) {
            throw new JdbcConnectorException("Data source '" + name + "': default-max-rows must be at least 1");
        }
        if (defaultQueryTimeout == null || defaultQueryTimeout.isNegative()) {
            throw new JdbcConnectorException(
                    "Data source '" + name + "': default-query-timeout must be zero (none) or positive");
        }
        if (defaultQueryTimeout.toSeconds() > Integer.MAX_VALUE) {
            throw new JdbcConnectorException("Data source '" + name + "': default-query-timeout is too large");
        }
    }

    static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        // never prints the password
        return "JdbcDataSourceProperties[url=" + url + ", username=" + username + ", jndiName=" + jndiName
                + ", beanName=" + beanName + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}

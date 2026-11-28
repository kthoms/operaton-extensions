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
package org.operaton.extensions.connector.jdbc.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.extensions.connector.jdbc.JdbcDataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code operaton.extensions.connector.jdbc.*}. Data sources are named; a call selects one with its {@code datasource}
 * input (default {@code default}).
 */
@ConfigurationProperties(prefix = "operaton.extensions.connector.jdbc")
public class JdbcConnectorProperties {

    /** Enables the JDBC connector. */
    private boolean enabled = true;

    /**
     * The data sources by name. Each has a {@code url} (a HikariCP pool is created), a {@code jndi-name} or a
     * {@code bean-name} (an existing Spring DataSource bean).
     */
    private Map<String, JdbcDataSourceProperties> datasources = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, JdbcDataSourceProperties> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, JdbcDataSourceProperties> datasources) {
        this.datasources = datasources;
    }
}

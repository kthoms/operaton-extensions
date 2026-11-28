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

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that gives the {@code jdbc-query} and {@code jdbc-update} connectors and the JDBC delegates
 * their data sources. Register it in {@code processEnginePlugins}:
 *
 * <pre>{@code
 * JdbcDataSourceProperties business = new JdbcDataSourceProperties();
 * business.setUrl("jdbc:postgresql://localhost/business");
 * business.setUsername("app");
 * business.setPassword(secret);
 *
 * JdbcConnectorPlugin plugin = new JdbcConnectorPlugin();
 * plugin.setDataSources(Map.of("default", business));
 * configuration.getProcessEnginePlugins().add(plugin);
 * }</pre>
 *
 * The plugin needs the Operaton Connect plugin ({@code ConnectProcessEnginePlugin}) to run connectors from BPMN.
 *
 * <p>Pools created by the plugin live until {@link #close()}; call it after closing the engine. With a prebuilt registry
 * ({@link #JdbcConnectorPlugin(JdbcDataSourceRegistry)}) the caller owns its lifecycle. One plugin per engine.
 */
public class JdbcConnectorPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcConnectorPlugin.class);

    private Map<String, JdbcDataSourceProperties> dataSources = new LinkedHashMap<>();
    private Map<String, DataSource> dataSourceInstances = new LinkedHashMap<>();

    private volatile JdbcDataSourceRegistry registry;
    private volatile boolean ownsRegistry;
    private volatile String engineName;

    public JdbcConnectorPlugin() {}

    /** Uses a registry managed by the caller (for example a Spring bean); the plugin never closes it. */
    public JdbcConnectorPlugin(JdbcDataSourceRegistry registry) {
        this.registry = registry;
    }

    /** Data sources created from settings (pool from {@code url}, JNDI lookup), by name. */
    public void setDataSources(Map<String, JdbcDataSourceProperties> dataSources) {
        this.dataSources = new LinkedHashMap<>(dataSources);
    }

    /** Ready data sources by name, for example the application's own business {@code DataSource}. */
    public void setDataSourceInstances(Map<String, DataSource> dataSourceInstances) {
        this.dataSourceInstances = new LinkedHashMap<>(dataSourceInstances);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        if (registry == null) {
            registry = new JdbcDataSourceRegistry(dataSources, dataSourceInstances);
            ownsRegistry = true;
        }
        registry.verifyEngineParticipation(configuration);
        engineName = configuration.getProcessEngineName();
        JdbcConnectors.register(engineName, registry);
        LOG.info("JDBC connector registered for process engine '{}'", engineName);
    }

    /** Unregisters from the engine and closes the pools this plugin created. Idempotent. */
    @Override
    public void close() {
        JdbcDataSourceRegistry current = registry;
        if (current != null && engineName != null) {
            JdbcConnectors.unregister(engineName, current);
        }
        if (current != null && ownsRegistry) {
            current.close();
        }
    }
}

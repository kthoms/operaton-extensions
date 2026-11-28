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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;

/**
 * Locates the {@link JdbcDataSourceRegistry} for a call.
 *
 * <p>The Operaton Connect SPI instantiates connectors through {@code ServiceLoader} and keeps them in a JVM-wide table,
 * so a connector cannot be handed its configuration. This class is the one place where that state lives: a
 * thread-safe map from process engine name to registry (filled by {@link JdbcConnectorPlugin}), plus an optional shared
 * registry for use outside an engine. Inside an engine command the registry of that engine is used, so several engines
 * in one JVM keep separate data sources.
 */
public final class JdbcConnectors {

    private static final Map<String, JdbcDataSourceRegistry> BY_ENGINE = new ConcurrentHashMap<>();
    private static final AtomicReference<JdbcDataSourceRegistry> SHARED = new AtomicReference<>();

    private JdbcConnectors() {}

    /** Makes {@code registry} the one used by the engine called {@code engineName}. */
    public static void register(String engineName, JdbcDataSourceRegistry registry) {
        JdbcDataSourceRegistry previous = BY_ENGINE.putIfAbsent(engineName, registry);
        if (previous != null && previous != registry) {
            throw new JdbcConnectorException("A JDBC connector registry is already registered for process engine '"
                    + engineName + "'; one JdbcConnectorPlugin per engine");
        }
    }

    /** Removes the registration if it is {@code registry}. */
    public static void unregister(String engineName, JdbcDataSourceRegistry registry) {
        BY_ENGINE.remove(engineName, registry);
    }

    /** Sets the registry used when a call is not made inside a process engine command, or its engine has none. */
    public static void setShared(JdbcDataSourceRegistry registry) {
        SHARED.set(registry);
    }

    /** Clears the shared registry if it is {@code registry}. */
    public static void clearShared(JdbcDataSourceRegistry registry) {
        SHARED.compareAndSet(registry, null);
    }

    /**
     * The registry for the current call.
     *
     * @throws JdbcConnectorException if none is configured
     */
    public static JdbcDataSourceRegistry current() {
        ProcessEngineConfigurationImpl configuration = Context.getProcessEngineConfiguration();
        if (configuration != null) {
            JdbcDataSourceRegistry registry = BY_ENGINE.get(configuration.getProcessEngineName());
            if (registry != null) {
                return registry;
            }
        }
        JdbcDataSourceRegistry shared = SHARED.get();
        if (shared != null) {
            return shared;
        }
        throw new JdbcConnectorException("No JDBC data sources are configured"
                + (configuration == null
                        ? " (call made outside a process engine and no shared registry set)"
                        : " for process engine '" + configuration.getProcessEngineName()
                                + "'; register a JdbcConnectorPlugin with data sources"));
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.context.Context;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * The named S3 stores of one process engine. Validated when constructed; the SDK clients are
 * created on first use of a store and closed by {@link #close()}.
 *
 * <p>Thread-safe: the configuration is copied and immutable, stores are created atomically.
 */
public final class S3StoreRegistry implements AutoCloseable {

    private final Map<String, S3StoreConfig> configs;
    private final String defaultStore;
    private final Map<String, S3Store> stores = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param configs the stores by name; at least one
     * @param defaultStore the store used when a call names none; may be {@code null} when there is
     *     exactly one store
     */
    public S3StoreRegistry(Map<String, S3StoreConfig> configs, String defaultStore) {
        if (configs == null || configs.isEmpty()) {
            throw new S3ConnectorException(
                    "No S3 store configured: define at least one store under operaton.extensions.connector.s3.stores");
        }
        configs.forEach((name, config) -> {
            if (name == null || name.isBlank()) {
                throw new S3ConnectorException("An S3 store needs a non-blank name");
            }
            if (config == null) {
                throw new S3ConnectorException("S3 store '" + name + "' has no configuration");
            }
            config.validate(name);
        });
        if (defaultStore != null && !defaultStore.isBlank() && !configs.containsKey(defaultStore)) {
            throw new S3ConnectorException(
                    "The default S3 store '" + defaultStore + "' is not configured; configured: " + configs.keySet());
        }
        this.configs = new LinkedHashMap<>(configs);
        this.defaultStore = defaultStore == null || defaultStore.isBlank() ? null : defaultStore;
    }

    /**
     * The registry of the engine that executes the current command.
     *
     * @throws S3ConnectorException when called outside a command or when {@link S3ConnectorPlugin}
     *     is not registered with the engine
     */
    public static S3StoreRegistry current() {
        ProcessEngineConfigurationImpl configuration;
        try {
            configuration = Context.getProcessEngineConfiguration();
        } catch (IllegalStateException e) {
            throw new S3ConnectorException(
                    "No S3 store registry: the S3 connector must run inside a process engine command", e);
        }
        if (configuration == null) {
            throw new S3ConnectorException(
                    "No S3 store registry: the S3 connector must run inside a process engine command");
        }
        S3ConnectorPlugin found = find(configuration.getProcessEnginePlugins());
        if (found != null) {
            return found.getRegistry();
        }
        throw new S3ConnectorException("S3ConnectorPlugin is not registered with process engine '"
                + configuration.getProcessEngineName() + "': add it to the engine's processEnginePlugins");
    }

    /** Finds the plugin, also inside the composite plugin that Spring Boot wraps plugins in. */
    private static S3ConnectorPlugin find(List<ProcessEnginePlugin> plugins) {
        for (ProcessEnginePlugin plugin : plugins) {
            if (plugin instanceof S3ConnectorPlugin s3Plugin) {
                return s3Plugin;
            }
            if (plugin instanceof CompositeProcessEnginePlugin composite) {
                S3ConnectorPlugin nested = find(composite.getPlugins());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * The store with the given name, or the default store when {@code name} is {@code null}.
     *
     * @throws S3ConnectorException for an unknown name, or when no default can be determined
     */
    public S3Store store(String name) {
        if (closed) {
            throw new S3ConnectorException("The S3 store registry is closed");
        }
        String resolved = name;
        if (resolved == null || resolved.isBlank()) {
            resolved = defaultStore;
            if (resolved == null && configs.size() == 1) {
                resolved = configs.keySet().iterator().next();
            }
            if (resolved == null) {
                throw new S3ConnectorException(
                        "No 'store' given and no default store configured; configured stores: " + configs.keySet());
            }
        }
        S3StoreConfig config = configs.get(resolved);
        if (config == null) {
            throw new S3ConnectorException(
                    "Unknown S3 store '" + resolved + "'; configured stores: " + configs.keySet());
        }
        return stores.computeIfAbsent(resolved, n -> {
            try {
                return new S3Store(n, config);
            } catch (SdkException e) {
                throw new S3ConnectorException(
                        "Cannot create the client of S3 store '" + n + "': " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        stores.values().forEach(S3Store::close);
        stores.clear();
    }
}

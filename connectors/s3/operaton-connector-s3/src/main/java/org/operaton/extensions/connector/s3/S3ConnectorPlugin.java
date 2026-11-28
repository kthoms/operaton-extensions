/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * Engine plugin that gives the S3 connectors ({@code s3-put}, {@code s3-get}, {@code s3-list},
 * {@code s3-copy}, {@code s3-delete}, {@code s3-presign}) and the S3 delegates their named stores.
 * The connectors themselves are registered through the Connect SPI and need no plugin; the plugin
 * only supplies the configuration, per engine.
 *
 * <pre>{@code
 * S3StoreConfig documents = new S3StoreConfig();
 * documents.setBucket("documents");
 * S3ConnectorPlugin plugin = new S3ConnectorPlugin();
 * plugin.setStores(Map.of("documents", documents));
 * configuration.getProcessEnginePlugins().add(plugin);
 * }</pre>
 *
 * <p>Thread-safe once the engine is built. Call {@link #close()} after closing the engine to
 * release the HTTP connections.
 */
public class S3ConnectorPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private Map<String, S3StoreConfig> stores = new LinkedHashMap<>();
    private String defaultStore;
    private S3StoreRegistry registry;

    public S3ConnectorPlugin() {}

    /** Uses an existing registry (for example a Spring bean); the plugin does not own it. */
    public S3ConnectorPlugin(S3StoreRegistry registry) {
        this.registry = registry;
    }

    /** The stores by name. */
    public void setStores(Map<String, S3StoreConfig> stores) {
        this.stores = new LinkedHashMap<>(stores);
    }

    /** The store used when a call names none; optional when exactly one store is configured. */
    public void setDefaultStore(String defaultStore) {
        this.defaultStore = defaultStore;
    }

    @Override
    public synchronized void preInit(ProcessEngineConfigurationImpl configuration) {
        if (registry == null) {
            registry = new S3StoreRegistry(stores, defaultStore);
        }
    }

    /** The registry, available after the engine configuration was initialised. */
    public synchronized S3StoreRegistry getRegistry() {
        if (registry == null) {
            throw new IllegalStateException("The plugin was not initialised by a process engine yet");
        }
        return registry;
    }

    @Override
    public synchronized void close() {
        if (registry != null) {
            registry.close();
        }
    }
}

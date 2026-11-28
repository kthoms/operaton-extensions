/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * class org.camunda.community.batch.plugin.CustomBatchHandlerPlugin, adapted to org.operaton and
 * extended with validation and history time to live.
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.operaton.bpm.engine.impl.batch.BatchJobHandler;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that registers custom batch handlers with the engine, so that the engine can
 * create their jobs and run them, also after a restart.
 *
 * <p>Register one instance in the engine configuration's {@code processEnginePlugins}, on every node
 * that creates or runs custom batches:
 *
 * <pre>{@code
 * configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(new RecalculateTotalsHandler()));
 * }</pre>
 *
 * <p>The plugin adds the handlers to the configuration's {@code customBatchJobHandlers} before the
 * engine initialises. It refuses to start the engine when two handlers share a type, when a type is
 * used by the engine itself, or (after initialisation) when another registration replaced a handler.
 * Configure it through the setters before the engine is built; the instance is not changed afterwards.
 */
public class CustomBatchPlugin extends AbstractProcessEnginePlugin {

    private static final Logger LOG = LoggerFactory.getLogger(CustomBatchPlugin.class);

    private final List<AbstractCustomBatchJobHandler<?>> handlers = new ArrayList<>();
    private final Map<String, String> historyTimeToLive = new LinkedHashMap<>();

    /** Creates a plugin without handlers; add them with {@link #addHandler} or {@link #setHandlers}. */
    public CustomBatchPlugin() {}

    /**
     * Creates a plugin for the handlers.
     *
     * @param handlers the handlers to register
     */
    public CustomBatchPlugin(AbstractCustomBatchJobHandler<?>... handlers) {
        this.handlers.addAll(List.of(handlers));
    }

    /**
     * Creates a plugin for the handlers.
     *
     * @param handlers the handlers to register
     */
    public CustomBatchPlugin(Collection<? extends AbstractCustomBatchJobHandler<?>> handlers) {
        this.handlers.addAll(handlers);
    }

    /**
     * Adds a handler.
     *
     * @param handler the handler
     * @return this plugin
     */
    public CustomBatchPlugin addHandler(AbstractCustomBatchJobHandler<?> handler) {
        handlers.add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Replaces the handlers; for configuration by property (XML).
     *
     * @param handlers the handlers
     */
    public void setHandlers(List<AbstractCustomBatchJobHandler<?>> handlers) {
        this.handlers.clear();
        this.handlers.addAll(handlers);
    }

    /** The registered handlers; an unmodifiable view. */
    public List<AbstractCustomBatchJobHandler<?>> getHandlers() {
        return List.copyOf(handlers);
    }

    /**
     * How long the history of finished batches of a type is kept, as ISO 8601 durations by batch type
     * (for example {@code recalculate-totals -> P30D}). Entries are added to the engine's
     * {@code batchOperationsForHistoryCleanup}; the engine validates them at startup. Types without an
     * entry follow the engine's {@code batchOperationHistoryTimeToLive}.
     *
     * @param historyTimeToLive duration by batch type
     */
    public void setHistoryTimeToLive(Map<String, String> historyTimeToLive) {
        this.historyTimeToLive.clear();
        this.historyTimeToLive.putAll(historyTimeToLive);
    }

    /** The configured history time to live by batch type; an unmodifiable copy. */
    public Map<String, String> getHistoryTimeToLive() {
        return Map.copyOf(historyTimeToLive);
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        Map<String, AbstractCustomBatchJobHandler<?>> byType = new HashMap<>();
        for (AbstractCustomBatchJobHandler<?> handler : handlers) {
            AbstractCustomBatchJobHandler<?> previous = byType.put(handler.getType(), handler);
            if (previous != null && previous != handler) {
                throw new IllegalStateException("Two custom batch handlers use the type '" + handler.getType() + "': "
                        + previous.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
        for (String type : historyTimeToLive.keySet()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("historyTimeToLive names the batch type '" + type
                        + "', but no handler of this plugin has it; known types: " + byType.keySet());
            }
        }

        List<BatchJobHandler<?>> custom = configuration.getCustomBatchJobHandlers();
        if (custom == null) {
            custom = new ArrayList<>();
            configuration.setCustomBatchJobHandlers(custom);
        }
        for (AbstractCustomBatchJobHandler<?> handler : byType.values()) {
            if (!custom.contains(handler)) {
                custom.add(handler);
            }
        }

        if (!historyTimeToLive.isEmpty()) {
            Map<String, String> cleanup = configuration.getBatchOperationsForHistoryCleanup();
            if (cleanup == null) {
                cleanup = new HashMap<>();
                configuration.setBatchOperationsForHistoryCleanup(cleanup);
            }
            cleanup.putAll(historyTimeToLive);
        }
        LOG.debug("Registered the custom batch types {}", byType.keySet());
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        for (AbstractCustomBatchJobHandler<?> handler : handlers) {
            BatchJobHandler<?> registered = configuration.getBatchHandlers().get(handler.getType());
            if (registered != handler) {
                throw new IllegalStateException("The custom batch type '" + handler.getType()
                        + "' is registered with another handler (" + registered
                        + "); batch types must be unique in an engine");
            }
            if (configuration.getJobHandlers().get(handler.getType()) != handler) {
                throw new IllegalStateException("The job handler type '" + handler.getType()
                        + "' is used by another job handler; batch types must be unique in an engine");
            }
        }
    }
}

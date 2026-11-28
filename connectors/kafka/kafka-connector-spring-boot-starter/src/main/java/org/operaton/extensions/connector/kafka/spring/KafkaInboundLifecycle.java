/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.spring;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.extensions.connector.kafka.KafkaConnectorPlugin;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the inbound consumers once the application context is complete (after the engine and the auto-deployment)
 * and stops them first on shutdown, before the engine goes away.
 */
class KafkaInboundLifecycle implements SmartLifecycle {

    private final KafkaConnectorPlugin plugin;
    private final ProcessEngine processEngine;
    private boolean running;

    KafkaInboundLifecycle(KafkaConnectorPlugin plugin, ProcessEngine processEngine) {
        this.plugin = plugin;
        this.processEngine = processEngine;
    }

    @Override
    public synchronized void start() {
        plugin.startConsumers(processEngine);
        running = true;
    }

    @Override
    public synchronized void stop() {
        plugin.stopConsumers();
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}

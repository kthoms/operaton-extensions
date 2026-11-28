/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.connect.plugin.impl.ConnectProcessEnginePlugin;

/** An embedded engine (H2) with the connect plugin and the given Kafka plugin. */
public final class EngineSupport {

    private EngineSupport() {}

    public static KafkaConnectorPlugin plugin() {
        KafkaConnectorPlugin plugin = new KafkaConnectorPlugin();
        plugin.setBootstrapServers(KafkaBroker.bootstrapServers());
        plugin.setAutoStartConsumers(false);
        return plugin;
    }

    public static ProcessEngineExtension engine(KafkaConnectorPlugin plugin) {
        return ProcessEngineExtension.builder()
                .closeEngineAfterAllTests()
                .configurator(configuration -> {
                    List<ProcessEnginePlugin> plugins = new ArrayList<>();
                    plugins.add(new ConnectProcessEnginePlugin());
                    plugins.add(plugin);
                    configuration.setProcessEnginePlugins(plugins);
                    configuration.setBeans(new java.util.HashMap<>());
                })
                .build();
    }
}

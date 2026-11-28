/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.operaton.spin.plugin.impl.SpinProcessEnginePlugin;

/** Embedded engines (in-memory H2) with Connect, Spin and the AI LLM plugin pointing at a stub. */
final class TestEngines {

    private TestEngines() {}

    /** A plugin with model {@code default} (OpenAI-compatible) at the stub. */
    static AiLlmPlugin plugin(LlmStubServer stub) {
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider("openai");
        config.setBaseUrl(stub.baseUrl());
        config.setModel("gpt-test");
        config.setApiKey("sk-test");
        config.setMaxRetries(0);
        AiLlmPlugin plugin = new AiLlmPlugin();
        plugin.addModel("default", config);
        return plugin;
    }

    static ProcessEngine build(String name, AiLlmPlugin aiLlmPlugin) {
        return build(name, aiLlmPlugin, configuration -> {});
    }

    static ProcessEngine build(
            String name, AiLlmPlugin aiLlmPlugin, Consumer<ProcessEngineConfigurationImpl> customizer) {
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName(name);
        configuration.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=1000");
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setJobExecutorActivate(false);
        configuration.setHistory("full");
        configuration.setDefaultSerializationFormat("application/json");
        List<ProcessEnginePlugin> plugins = new ArrayList<>();
        plugins.add(new ConnectProcessEnginePlugin());
        plugins.add(new SpinProcessEnginePlugin());
        if (aiLlmPlugin != null) {
            plugins.add(aiLlmPlugin);
        }
        configuration.setProcessEnginePlugins(plugins);
        customizer.accept(configuration);
        return configuration.buildProcessEngine();
    }
}

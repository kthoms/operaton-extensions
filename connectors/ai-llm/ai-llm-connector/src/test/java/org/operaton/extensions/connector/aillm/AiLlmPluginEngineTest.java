/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/** Engine start-up validation and how the connectors find the plugin. */
class AiLlmPluginEngineTest {

    @Test
    void invalidConfigurationStopsTheEngineFromStarting() {
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider("anthropic");
        config.setModel("claude");
        AiLlmPlugin plugin = new AiLlmPlugin();
        plugin.addModel("default", config);

        assertThatThrownBy(() -> TestEngines.build("llm-invalid", plugin))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("Model 'default': api-key is required");
    }

    @Test
    void connectorWithoutPluginFailsWithAnExplanation() {
        ProcessEngine engine = TestEngines.build("llm-no-plugin", null);
        try {
            engine.getRepositoryService()
                    .createDeployment()
                    .addClasspathResource("bpmn/llm-prompt.bpmn")
                    .deploy();

            assertThatThrownBy(() ->
                            engine.getRuntimeService().startProcessInstanceByKey("llm-prompt", Map.of("maxTokens", 10)))
                    .isInstanceOf(AiLlmException.class)
                    .hasMessageContaining("has no AiLlmPlugin");
        } finally {
            engine.close();
        }
    }

    @Test
    void pluginIsFoundInsideACompositePlugin() {
        LlmStubServer stub = new LlmStubServer();
        AiLlmPlugin plugin = TestEngines.plugin(stub);
        ProcessEngine engine = TestEngines.build(
                "llm-composite",
                null,
                configuration -> configuration.getProcessEnginePlugins().add(new CompositeProcessEnginePlugin(plugin)));
        try {
            LlmService service =
                    AiLlmPlugin.serviceOf((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration());
            assertThat(service.modelNames()).containsExactly("default");
            assertThat(service.defaultModel()).isEqualTo("default");
            assertThat(Map.of("plugin", plugin.getService())).containsValue(service);
        } finally {
            engine.close();
            stub.close();
        }
    }
}

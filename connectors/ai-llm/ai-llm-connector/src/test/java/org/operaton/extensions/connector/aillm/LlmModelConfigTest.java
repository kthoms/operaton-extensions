/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmModelConfigTest {

    static LlmModelConfig config(String provider) {
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider(provider);
        config.setModel("m");
        return config;
    }

    @Test
    void openAiCompatibleNeedsNoKey() {
        LlmModelConfig config = config("openai");
        config.validate("local");
        assertThat(config.structuredOutputMode()).isEqualTo(StructuredOutputMode.JSON_OBJECT);
        assertThat(config("anthropic").structuredOutputMode()).isEqualTo(StructuredOutputMode.PROMPT);
    }

    @Test
    void invalidValuesAreRejectedWithTheModelName() {
        assertThatThrownBy(() -> config("gemini").validate("x"))
                .hasMessageContaining("Model 'x': Unknown provider 'gemini'");
        assertThatThrownBy(() -> config("anthropic").validate("x")).hasMessageContaining("api-key is required");
        LlmModelConfig azure = config("azure-openai");
        azure.setApiKey("k");
        assertThatThrownBy(() -> azure.validate("x")).hasMessageContaining("base-url is required");
        LlmModelConfig noModel = config("openai");
        noModel.setModel(" ");
        assertThatThrownBy(() -> noModel.validate("x")).hasMessageContaining("model is required");
        LlmModelConfig hot = config("openai");
        hot.setTemperature(3.0);
        assertThatThrownBy(() -> hot.validate("x")).hasMessageContaining("temperature");
        LlmModelConfig zero = config("openai");
        zero.setTimeout(Duration.ZERO);
        assertThatThrownBy(() -> zero.validate("x")).hasMessageContaining("timeout");
        LlmModelConfig url = config("openai");
        url.setBaseUrl("localhost:11434");
        assertThatThrownBy(() -> url.validate("x")).hasMessageContaining("base-url must be an http(s) URL");
        LlmModelConfig mode = config("openai");
        mode.setStructuredOutput("xml");
        assertThatThrownBy(() -> mode.validate("x")).hasMessageContaining("structured-output");
    }

    @Test
    void toStringMasksTheApiKey() {
        LlmModelConfig config = config("openai");
        config.setApiKey("sk-very-secret");
        assertThat(config.toString()).contains("apiKey=****").doesNotContain("sk-very-secret");
    }

    @Test
    void serviceRejectsMissingModelsAndUnknownDefault() {
        assertThatThrownBy(() -> new LlmService(Map.of(), "default", 2, 10, List.of()))
                .hasMessageContaining("No model is configured");
        assertThatThrownBy(() -> new LlmService(Map.of("fast", config("openai")), "default", 2, 10, List.of()))
                .hasMessageContaining("default-model 'default' is not configured");
        assertThatThrownBy(() -> new LlmService(Map.of("default", config("openai")), "default", 2, 0, List.of()))
                .hasMessageContaining("agent-max-iterations");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.internal;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import org.operaton.extensions.connector.aillm.LlmModelConfig;

/**
 * Builds a LangChain4j {@link ChatModel} from a validated {@link LlmModelConfig}. LangChain4j chat
 * models are thread-safe and are built once per configured model. Request and response logging of
 * LangChain4j stays off: it would write prompts and process data to the log.
 */
public final class ChatModelFactory {

    private ChatModelFactory() {}

    public static ChatModel create(LlmModelConfig config) {
        return switch (config.providerType()) {
            case OPENAI ->
                OpenAiChatModel.builder()
                        .baseUrl(blankToNull(config.getBaseUrl()))
                        .apiKey(blankToNull(config.getApiKey()))
                        .modelName(config.getModel())
                        .temperature(config.getTemperature())
                        .maxTokens(config.getMaxTokens())
                        .timeout(config.getTimeout())
                        .maxRetries(config.getMaxRetries())
                        .logRequests(false)
                        .logResponses(false)
                        .build();
            case AZURE_OPENAI ->
                OpenAiChatModel.builder()
                        .baseUrl(config.getBaseUrl())
                        .customHeaders(Map.of("api-key", config.getApiKey()))
                        .modelName(config.getModel())
                        .temperature(config.getTemperature())
                        .maxTokens(config.getMaxTokens())
                        .timeout(config.getTimeout())
                        .maxRetries(config.getMaxRetries())
                        .logRequests(false)
                        .logResponses(false)
                        .build();
            case ANTHROPIC -> {
                AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                        .apiKey(config.getApiKey())
                        .modelName(config.getModel())
                        .temperature(config.getTemperature())
                        .timeout(config.getTimeout())
                        .maxRetries(config.getMaxRetries())
                        .logRequests(false)
                        .logResponses(false);
                if (blankToNull(config.getBaseUrl()) != null) {
                    builder.baseUrl(config.getBaseUrl());
                }
                if (config.getMaxTokens() != null) {
                    builder.maxTokens(config.getMaxTokens());
                }
                yield builder.build();
            }
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

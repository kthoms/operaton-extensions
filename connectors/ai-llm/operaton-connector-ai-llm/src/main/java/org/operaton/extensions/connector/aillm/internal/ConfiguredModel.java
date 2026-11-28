/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.internal;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.LlmModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One configured model: its name, configuration and LangChain4j chat model. Immutable and
 * thread-safe (LangChain4j chat models are).
 */
public final class ConfiguredModel {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredModel.class);

    private final String name;
    private final LlmModelConfig config;
    private final ChatModel chatModel;

    public ConfiguredModel(String name, LlmModelConfig config, ChatModel chatModel) {
        this.name = name;
        this.config = new LlmModelConfig(config);
        this.chatModel = chatModel;
    }

    public String name() {
        return name;
    }

    /** A copy of the configuration. */
    public LlmModelConfig config() {
        return new LlmModelConfig(config);
    }

    /**
     * Calls the model once. Token usage is logged at DEBUG; prompts and answers are never logged.
     *
     * @throws AiLlmException when the provider call fails, with the model name and the cause
     */
    public ChatResponse chat(List<ChatMessage> messages, ChatRequestParameters parameters) {
        ChatRequest request =
                ChatRequest.builder().messages(messages).parameters(parameters).build();
        ChatResponse response;
        try {
            response = chatModel.chat(request);
        } catch (RuntimeException e) {
            throw new AiLlmException(
                    "Call of model '" + name + "' (" + config.getProvider() + ", " + config.getModel() + ") failed: "
                            + e.getMessage(),
                    e);
        }
        if (response == null || response.aiMessage() == null) {
            throw new AiLlmException("Model '" + name + "' returned no message");
        }
        if (LOG.isDebugEnabled()) {
            var usage = response.tokenUsage();
            LOG.debug(
                    "LLM call model={} provider={} providerModel={} inputTokens={} outputTokens={} totalTokens={} finishReason={}",
                    name,
                    config.getProvider(),
                    response.modelName() != null ? response.modelName() : config.getModel(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    usage == null ? null : usage.totalTokenCount(),
                    response.finishReason());
        }
        return response;
    }

    /** The model name the provider reported, or the configured one. */
    public String reportedModelName(ChatResponse response) {
        return response.modelName() != null ? response.modelName() : config.getModel();
    }
}

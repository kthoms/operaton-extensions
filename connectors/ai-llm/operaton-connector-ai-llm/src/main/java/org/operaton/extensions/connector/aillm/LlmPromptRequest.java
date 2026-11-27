/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/**
 * The prompt part shared by every operation.
 *
 * @param model name of the configured model, {@code null} for the default model
 * @param systemPrompt system prompt template, may be {@code null}
 * @param userPrompt user prompt template, required
 * @param temperature overrides the model's temperature, may be {@code null}
 * @param maxTokens overrides the model's max tokens, may be {@code null}
 */
public record LlmPromptRequest(
        String model, String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {

    public LlmPromptRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new AiLlmException("userPrompt is required");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new AiLlmException("temperature must be between 0.0 and 2.0, was " + temperature);
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new AiLlmException("maxTokens must be at least 1, was " + maxTokens);
        }
    }

    /** A request with only a user prompt, for the default model. */
    public static LlmPromptRequest of(String userPrompt) {
        return new LlmPromptRequest(null, null, userPrompt, null, null);
    }
}

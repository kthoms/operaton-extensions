/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The chat API a configured model is called through. */
public enum LlmProvider {

    /**
     * The OpenAI chat completions API and every server compatible with it (OpenAI, Ollama, vLLM, LM
     * Studio, ...), selected by the base URL. The API key is sent as a bearer token and is optional.
     */
    OPENAI("openai"),

    /**
     * Azure OpenAI through its OpenAI-compatible v1 endpoint ({@code https://<resource>.openai.azure.com/openai/v1}).
     * The base URL is required, the model is the deployment name and the API key is sent in the
     * {@code api-key} header.
     */
    AZURE_OPENAI("azure-openai"),

    /** The Anthropic messages API. The API key is required. */
    ANTHROPIC("anthropic");

    private final String id;

    LlmProvider(String id) {
        this.id = id;
    }

    /** The configuration value, e.g. {@code openai}. */
    public String id() {
        return id;
    }

    /** Parses a configuration value (case-insensitive, {@code _} and {@code -} are equivalent). */
    public static LlmProvider fromId(String value) {
        if (value == null || value.isBlank()) {
            throw new AiLlmException("provider is required; one of " + ids());
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (LlmProvider provider : values()) {
            if (provider.id.equals(normalized)) {
                return provider;
            }
        }
        throw new AiLlmException("Unknown provider '" + value + "'; one of " + ids());
    }

    private static String ids() {
        return Arrays.stream(values()).map(LlmProvider::id).collect(Collectors.joining(", "));
    }
}

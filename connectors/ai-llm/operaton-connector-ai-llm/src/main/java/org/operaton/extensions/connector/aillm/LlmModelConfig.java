/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.time.Duration;

/**
 * One named model: which provider, endpoint and model to call and with which defaults. A JavaBean,
 * so it can be configured from {@code operaton.cfg.xml}, {@code bpm-platform.xml} or Spring Boot
 * properties ({@code operaton.extensions.connector.ai-llm.models.<name>.*}).
 *
 * <p>The API key comes only from configuration (and so from the environment through property
 * placeholders). It is never accepted as process data and never logged: {@link #toString()} masks
 * it.
 *
 * <p>Mutable while the engine is configured; {@link AiLlmPlugin} copies the values when the engine
 * starts, later changes have no effect.
 */
public class LlmModelConfig {

    /** Default model call timeout when none is configured. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Default number of HTTP retries LangChain4j makes for a failed provider call. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private String provider;
    private String baseUrl;
    private String model;
    private String apiKey;
    private Double temperature;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Integer maxTokens;
    private Integer maxRetries = DEFAULT_MAX_RETRIES;
    private String structuredOutput;

    public LlmModelConfig() {}

    /** Copy constructor. */
    public LlmModelConfig(LlmModelConfig other) {
        this.provider = other.provider;
        this.baseUrl = other.baseUrl;
        this.model = other.model;
        this.apiKey = other.apiKey;
        this.temperature = other.temperature;
        this.timeout = other.timeout;
        this.maxTokens = other.maxTokens;
        this.maxRetries = other.maxRetries;
        this.structuredOutput = other.structuredOutput;
    }

    /**
     * Checks the values and fails with a message naming the model and the property.
     *
     * @param name the name the model is configured under
     */
    public void validate(String name) {
        String prefix = "Model '" + name + "': ";
        LlmProvider parsedProvider;
        try {
            parsedProvider = LlmProvider.fromId(provider);
        } catch (AiLlmException e) {
            throw new AiLlmException(prefix + e.getMessage(), e);
        }
        if (model == null || model.isBlank()) {
            throw new AiLlmException(prefix + "model is required (the provider's model or deployment name)");
        }
        if (parsedProvider == LlmProvider.AZURE_OPENAI && (baseUrl == null || baseUrl.isBlank())) {
            throw new AiLlmException(
                    prefix + "base-url is required for azure-openai (https://<resource>.openai.azure.com/openai/v1)");
        }
        if (parsedProvider != LlmProvider.OPENAI && (apiKey == null || apiKey.isBlank())) {
            throw new AiLlmException(prefix + "api-key is required for provider " + parsedProvider.id());
        }
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.matches("(?i)https?://.+")) {
            throw new AiLlmException(prefix + "base-url must be an http(s) URL, was '" + baseUrl + "'");
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new AiLlmException(prefix + "temperature must be between 0.0 and 2.0, was " + temperature);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new AiLlmException(prefix + "timeout must be positive, was " + timeout);
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new AiLlmException(prefix + "max-tokens must be at least 1, was " + maxTokens);
        }
        if (maxRetries == null || maxRetries < 0 || maxRetries > 10) {
            throw new AiLlmException(prefix + "max-retries must be between 0 and 10, was " + maxRetries);
        }
        if (structuredOutput != null) {
            StructuredOutputMode mode;
            try {
                mode = StructuredOutputMode.fromId(structuredOutput);
            } catch (AiLlmException e) {
                throw new AiLlmException(prefix + e.getMessage(), e);
            }
            if (parsedProvider == LlmProvider.ANTHROPIC && mode != StructuredOutputMode.PROMPT) {
                throw new AiLlmException(prefix + "structured-output " + mode.id()
                        + " is an OpenAI response format; use 'prompt' for anthropic");
            }
        }
    }

    /** The provider, parsed. */
    public LlmProvider providerType() {
        return LlmProvider.fromId(provider);
    }

    /**
     * The structured-output mode: the configured one, or {@code json-object} for the OpenAI-compatible
     * providers and {@code prompt} for Anthropic.
     */
    public StructuredOutputMode structuredOutputMode() {
        if (structuredOutput != null) {
            return StructuredOutputMode.fromId(structuredOutput);
        }
        return providerType() == LlmProvider.ANTHROPIC ? StructuredOutputMode.PROMPT : StructuredOutputMode.JSON_OBJECT;
    }

    public String getProvider() {
        return provider;
    }

    /** {@code openai} (OpenAI-compatible), {@code azure-openai} or {@code anthropic}. Required. */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Base URL of the API, e.g. {@code http://localhost:11434/v1} for Ollama. Optional for {@code openai}
     * (default {@code https://api.openai.com/v1}) and {@code anthropic} (default
     * {@code https://api.anthropic.com/v1/}), required for {@code azure-openai}.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    /** The provider's model name (deployment name for Azure). Required. */
    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    /** API key. Optional for {@code openai} (local servers need none), required otherwise. Never logged. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Double getTemperature() {
        return temperature;
    }

    /** Sampling temperature, 0.0 to 2.0; the provider's default when unset. */
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /** Connect and read timeout of one model call. Default 60 seconds. */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /** {@link #setTimeout(Duration)} in seconds, for XML configuration. */
    public void setTimeoutSeconds(long seconds) {
        this.timeout = Duration.ofSeconds(seconds);
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    /** Maximum number of tokens the model may generate per call; the provider's default when unset. */
    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    /** HTTP retries of a failed provider call (LangChain4j), 0 to 10. Default 2. */
    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getStructuredOutput() {
        return structuredOutput;
    }

    /** {@code prompt}, {@code json-object} or {@code json-schema}, see {@link StructuredOutputMode}. */
    public void setStructuredOutput(String structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    @Override
    public String toString() {
        return "LlmModelConfig[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model + ", apiKey="
                + (apiKey == null || apiKey.isEmpty() ? "<none>" : "****") + ", temperature=" + temperature
                + ", timeout=" + timeout + ", maxTokens=" + maxTokens + ", maxRetries=" + maxRetries
                + ", structuredOutput=" + structuredOutput + "]";
    }
}

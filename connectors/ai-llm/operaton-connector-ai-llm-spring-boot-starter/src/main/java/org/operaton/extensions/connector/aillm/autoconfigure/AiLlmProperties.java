/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.extensions.connector.aillm.AiLlmPlugin;
import org.operaton.extensions.connector.aillm.LlmModelConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.connector.ai-llm.*}, mapped onto {@link AiLlmPlugin}. */
@ConfigurationProperties(prefix = AiLlmProperties.PREFIX)
public class AiLlmProperties {

    public static final String PREFIX = "operaton.extensions.connector.ai-llm";

    /** Whether to register the AiLlmPlugin. */
    private boolean enabled = true;

    /** Name of the model used when a task names none. */
    private String defaultModel = AiLlmPlugin.DEFAULT_MODEL_NAME;

    /** Retries of llm-extract after an answer that does not validate (0 to 10). */
    private int extractRetries = AiLlmPlugin.DEFAULT_EXTRACT_RETRIES;

    /** Maximum number of model calls of llm-agent (1 to 50). */
    private int agentMaxIterations = AiLlmPlugin.DEFAULT_AGENT_MAX_ITERATIONS;

    /** The named models: models.<name>.provider, base-url, model, api-key, temperature, timeout, max-tokens, ... */
    private Map<String, LlmModelConfig> models = new LinkedHashMap<>();

    /** Tools agents may enable: tools.<name>.bean, method, description. */
    private Map<String, Tool> tools = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public int getExtractRetries() {
        return extractRetries;
    }

    public void setExtractRetries(int extractRetries) {
        this.extractRetries = extractRetries;
    }

    public int getAgentMaxIterations() {
        return agentMaxIterations;
    }

    public void setAgentMaxIterations(int agentMaxIterations) {
        this.agentMaxIterations = agentMaxIterations;
    }

    public Map<String, LlmModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, LlmModelConfig> models) {
        this.models = models;
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    public void setTools(Map<String, Tool> tools) {
        this.tools = tools;
    }

    /** One tool: a public method of a Spring bean. */
    public static class Tool {

        /** Name of the Spring bean. */
        private String bean;

        /** Name of the public method (must not be overloaded). */
        private String method;

        /** What the tool does; the model chooses tools by it. */
        private String description;

        public String getBean() {
            return bean;
        }

        public void setBean(String bean) {
            this.bean = bean;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.extensions.connector.aillm.tool.LlmTool;

/**
 * Process engine plugin that holds the configured models and tools of the AI LLM connector. The
 * connectors ({@code llm-prompt}, {@code llm-extract}, {@code llm-agent}) and the delegates find it
 * through the engine they run in, so several engines in one JVM can use different models.
 *
 * <p>Configure it with its setters ({@code operaton.cfg.xml}, {@code bpm-platform.xml}) or through
 * the Spring Boot starter. The configuration is validated when the engine starts; an invalid value
 * stops the engine from starting. The connectors additionally need the Connect plugin
 * ({@code operaton-engine-plugin-connect}).
 */
public class AiLlmPlugin extends AbstractProcessEnginePlugin {

    /** Name of the model used when a call names none, unless {@link #setDefaultModel} says otherwise. */
    public static final String DEFAULT_MODEL_NAME = "default";

    /** Default retries of {@code llm-extract} after an invalid answer. */
    public static final int DEFAULT_EXTRACT_RETRIES = 2;

    /** Default maximum number of model calls of {@code llm-agent}. */
    public static final int DEFAULT_AGENT_MAX_ITERATIONS = 10;

    private Map<String, LlmModelConfig> models = new LinkedHashMap<>();
    private String defaultModel = DEFAULT_MODEL_NAME;
    private int extractRetries = DEFAULT_EXTRACT_RETRIES;
    private int agentMaxIterations = DEFAULT_AGENT_MAX_ITERATIONS;
    private List<LlmTool> tools = new ArrayList<>();

    private volatile LlmService service;

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        Map<String, LlmModelConfig> copies = new LinkedHashMap<>();
        models.forEach((name, config) -> copies.put(name, config == null ? null : new LlmModelConfig(config)));
        service = new LlmService(copies, defaultModel, extractRetries, agentMaxIterations, List.copyOf(tools));
    }

    /**
     * The service built when the engine started.
     *
     * @throws AiLlmException if the engine has not been initialized with this plugin yet
     */
    public LlmService getService() {
        LlmService current = service;
        if (current == null) {
            throw new AiLlmException("The AI LLM plugin has not been initialized by a process engine yet");
        }
        return current;
    }

    /**
     * Finds the plugin among an engine's plugins (also inside a {@link CompositeProcessEnginePlugin},
     * as Spring Boot registers them) and returns its service.
     *
     * @throws AiLlmException if the engine has no {@link AiLlmPlugin}
     */
    public static LlmService serviceOf(ProcessEngineConfigurationImpl configuration) {
        if (configuration == null) {
            throw new AiLlmException("No process engine configuration is active; the AI LLM connector runs only "
                    + "inside a process engine with the AiLlmPlugin");
        }
        AiLlmPlugin plugin = find(configuration.getProcessEnginePlugins());
        if (plugin == null) {
            throw new AiLlmException("The process engine '" + configuration.getProcessEngineName()
                    + "' has no AiLlmPlugin; register org.operaton.extensions.connector.aillm.AiLlmPlugin "
                    + "(or add the Spring Boot starter) and configure at least one model");
        }
        return plugin.getService();
    }

    private static AiLlmPlugin find(List<ProcessEnginePlugin> plugins) {
        if (plugins == null) {
            return null;
        }
        for (ProcessEnginePlugin plugin : plugins) {
            if (plugin instanceof AiLlmPlugin aiLlmPlugin) {
                return aiLlmPlugin;
            }
            if (plugin instanceof CompositeProcessEnginePlugin composite) {
                AiLlmPlugin nested = find(composite.getPlugins());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    public Map<String, LlmModelConfig> getModels() {
        return models;
    }

    /** The named models. At least one is required. */
    public void setModels(Map<String, LlmModelConfig> models) {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    /** Adds or replaces one named model. */
    public AiLlmPlugin addModel(String name, LlmModelConfig config) {
        models.put(name, config);
        return this;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    /** The model a call without {@code model} uses. Default {@value #DEFAULT_MODEL_NAME}. */
    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public int getExtractRetries() {
        return extractRetries;
    }

    /** Retries of {@code llm-extract} after an invalid answer, 0 to 10. Default 2. */
    public void setExtractRetries(int extractRetries) {
        this.extractRetries = extractRetries;
    }

    public int getAgentMaxIterations() {
        return agentMaxIterations;
    }

    /** Maximum number of model calls of {@code llm-agent}, 1 to 50. Default 10. */
    public void setAgentMaxIterations(int agentMaxIterations) {
        this.agentMaxIterations = agentMaxIterations;
    }

    public List<LlmTool> getTools() {
        return tools;
    }

    /** The tools an agent task may enable by name. */
    public void setTools(List<LlmTool> tools) {
        this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
    }

    /** Registers one tool. */
    public AiLlmPlugin addTool(LlmTool tool) {
        tools.add(tool);
        return this;
    }
}

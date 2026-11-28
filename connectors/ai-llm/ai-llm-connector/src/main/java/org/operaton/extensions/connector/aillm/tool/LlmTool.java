/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * A callable the agent ({@code llm-agent}) may use. Tools are registered explicitly on
 * {@link org.operaton.extensions.connector.aillm.AiLlmPlugin} and enabled per task by name; the
 * model can call nothing else. Implementations are shared by all executions and must be
 * thread-safe.
 */
public interface LlmTool {

    /** Name, description and parameter schema shown to the model. The name is the tool's identity. */
    ToolSpecification specification();

    /** The tool's name, {@code specification().name()}. */
    default String name() {
        return specification().name();
    }

    /**
     * Executes one call.
     *
     * @param argumentsJson the arguments the model sent, a JSON object
     * @param context the calling execution's context
     * @return the result text sent back to the model
     * @throws ToolRefusedException when the model's request is invalid (bad arguments, not allowed);
     *     its message is sent back to the model, which may correct itself
     * @throws RuntimeException on any other failure; it ends the agent loop and fails the task
     */
    String execute(String argumentsJson, LlmToolContext context);
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/**
 * Result of {@code llm-agent}.
 *
 * @param text the model's final answer
 * @param iterations number of model calls made
 * @param toolCalls number of tool calls the model requested
 * @param modelName the model that answered
 * @param usage token usage summed over all model calls
 */
public record LlmAgentResult(String text, int iterations, int toolCalls, String modelName, LlmUsage usage) {}

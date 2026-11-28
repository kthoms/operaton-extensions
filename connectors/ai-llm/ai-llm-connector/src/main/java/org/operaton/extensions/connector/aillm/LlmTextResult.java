/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/**
 * Result of {@code llm-prompt}.
 *
 * @param text the model's answer
 * @param modelName the model that answered, as reported by the provider (or as configured)
 * @param finishReason why the model stopped, e.g. {@code STOP} or {@code LENGTH}; {@code null} if not reported
 * @param usage token usage
 */
public record LlmTextResult(String text, String modelName, String finishReason, LlmUsage usage) {}

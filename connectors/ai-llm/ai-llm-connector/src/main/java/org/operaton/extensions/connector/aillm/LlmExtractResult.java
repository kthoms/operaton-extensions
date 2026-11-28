/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/**
 * Result of {@code llm-extract}: a JSON document that validated against the requested schema.
 *
 * @param json the document as JSON text (normalized, without code fences)
 * @param value the document parsed into {@code Map}, {@code List}, {@code String}, {@code Number},
 *     {@code Boolean} or {@code null}
 * @param attempts number of model calls it took, the first one included
 * @param modelName the model that answered
 * @param usage token usage summed over all attempts
 */
public record LlmExtractResult(String json, Object value, int attempts, String modelName, LlmUsage usage) {}

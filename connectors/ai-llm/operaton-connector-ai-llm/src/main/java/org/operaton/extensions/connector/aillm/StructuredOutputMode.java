/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How {@code llm-extract} asks a model for JSON. The JSON Schema is always part of the system
 * prompt and the answer is always validated; the mode only adds what the provider can enforce.
 */
public enum StructuredOutputMode {

    /** Instructions in the prompt only. Works with every provider. */
    PROMPT("prompt"),

    /** Additionally requests JSON mode ({@code response_format: json_object}); OpenAI-compatible providers only. */
    JSON_OBJECT("json-object"),

    /** Additionally sends the schema as {@code response_format: json_schema}; OpenAI-compatible providers that support it. */
    JSON_SCHEMA("json-schema");

    private final String id;

    StructuredOutputMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Parses a configuration value (case-insensitive, {@code _} and {@code -} are equivalent). */
    public static StructuredOutputMode fromId(String value) {
        String normalized =
                value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (StructuredOutputMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        throw new AiLlmException("Unknown structured-output mode '" + value + "'; one of "
                + Arrays.stream(values()).map(StructuredOutputMode::id).collect(Collectors.joining(", ")));
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import org.operaton.extensions.connector.aillm.AiLlmException;

/** Renders variable values as text for prompts and tool results. Stateless and thread-safe. */
public final class Values {

    /** Shared, thread-safe once configured. */
    public static final ObjectMapper JSON = new ObjectMapper();

    private Values() {}

    /**
     * Text for a value: a string as is; {@code null}, numbers, booleans, maps, collections and arrays
     * as JSON; dates as ISO-8601; any other object by its {@code toString()} (for example a Spin JSON
     * node, whose string form is its JSON).
     */
    public static String render(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value.getClass().isArray()) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new AiLlmException(
                        "Cannot render a value of type " + value.getClass().getName() + " as JSON: "
                                + e.getOriginalMessage(),
                        e);
            }
        }
        return String.valueOf(value);
    }

    /** Shortens text for error messages. */
    public static String abbreviate(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(" + text.length() + " chars)";
    }
}

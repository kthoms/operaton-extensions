/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.internal;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.extensions.connector.s3.S3ConnectorException;

/** Typed, fail-loud access to the parameters of one S3 call. Not API. */
public final class S3Params {

    private final String operation;
    private final Map<String, Object> values;

    public S3Params(String operation, Map<String, Object> values) {
        this.operation = operation;
        this.values = values;
    }

    public Object raw(String name) {
        return values.get(name);
    }

    /** A string parameter; blank counts as absent. */
    public String string(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    public String requiredString(String name) {
        String value = string(name);
        if (value == null) {
            throw new S3ConnectorException(operation + ": parameter '" + name + "' is required");
        }
        return value;
    }

    public boolean bool(String name, boolean defaultValue) {
        Object value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = value.toString().trim();
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        throw invalid(name, "a boolean", value);
    }

    public Long longValue(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw invalid(name, "a whole number", value);
        }
    }

    public Integer intValue(String name) {
        Long value = longValue(name);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw invalid(name, "an int", value);
        }
        return value.intValue();
    }

    /** A duration: {@link Duration}, a number of seconds, or an ISO-8601 string such as {@code PT15M}. */
    public Duration duration(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Duration d) {
            return d;
        }
        if (value instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        String text = value.toString().trim();
        try {
            return text.startsWith("P") || text.startsWith("p")
                    ? Duration.parse(text)
                    : Duration.ofSeconds(Long.parseLong(text));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw invalid(name, "an ISO-8601 duration (PT15M) or a number of seconds", value);
        }
    }

    /** A string map ({@code Map} of anything convertible to text); {@code null} when absent. */
    public Map<String, String> stringMap(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(name, "a map", value);
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k == null || v == null) {
                throw new S3ConnectorException(
                        operation + ": parameter '" + name + "' must not contain null keys or values");
            }
            result.put(k.toString(), v.toString());
        });
        return result;
    }

    private S3ConnectorException invalid(String name, String expected, Object value) {
        return new S3ConnectorException(operation + ": parameter '" + name + "' must be " + expected + ", was '" + value
                + "' (" + value.getClass().getSimpleName() + ")");
    }
}

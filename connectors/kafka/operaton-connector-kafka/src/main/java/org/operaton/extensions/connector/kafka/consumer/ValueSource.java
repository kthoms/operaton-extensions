/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a mapped value comes from: a parsed, immutable expression over an {@link InboundRecord}. Thread-safe.
 *
 * <table>
 *   <caption>Syntax</caption>
 *   <tr><td>{@code key}, {@code value}</td><td>the record key / the whole value, as text</td></tr>
 *   <tr><td>{@code topic}, {@code partition}, {@code offset}, {@code timestamp}</td><td>record metadata (numbers
 *       for the last three)</td></tr>
 *   <tr><td>{@code header:<name>}</td><td>the last header with that name, as text</td></tr>
 *   <tr><td>{@code $}, {@code $.a.b[0]}, {@code $['a.b']}</td><td>a path into the JSON value: text, number or
 *       boolean for scalars, the compact JSON text for objects and arrays</td></tr>
 *   <tr><td>{@code const:<text>}</td><td>a constant</td></tr>
 * </table>
 *
 * A trailing {@code ?} marks the source optional: when the value is absent (or JSON null) the result is
 * {@code null}; otherwise absence is a {@link MappingException}.
 */
public final class ValueSource {

    private enum Kind {
        KEY,
        VALUE,
        TOPIC,
        PARTITION,
        OFFSET,
        TIMESTAMP,
        HEADER,
        JSON_PATH,
        CONSTANT
    }

    private final String expression;
    private final Kind kind;
    private final String argument;
    private final List<Object> path;
    private final boolean optional;

    private ValueSource(String expression, Kind kind, String argument, List<Object> path, boolean optional) {
        this.expression = expression;
        this.kind = kind;
        this.argument = argument;
        this.path = path;
        this.optional = optional;
    }

    /**
     * Parses a source expression.
     *
     * @throws IllegalArgumentException for a malformed expression
     */
    public static ValueSource parse(String expression) {
        Objects.requireNonNull(expression, "source expression");
        String text = expression.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("source expression must not be blank");
        }
        if (text.startsWith("const:")) {
            return new ValueSource(expression, Kind.CONSTANT, text.substring("const:".length()), List.of(), false);
        }
        boolean optional = text.endsWith("?");
        if (optional) {
            text = text.substring(0, text.length() - 1);
        }
        switch (text) {
            case "key":
                return new ValueSource(expression, Kind.KEY, null, List.of(), optional);
            case "value":
                return new ValueSource(expression, Kind.VALUE, null, List.of(), optional);
            case "topic":
                return new ValueSource(expression, Kind.TOPIC, null, List.of(), optional);
            case "partition":
                return new ValueSource(expression, Kind.PARTITION, null, List.of(), optional);
            case "offset":
                return new ValueSource(expression, Kind.OFFSET, null, List.of(), optional);
            case "timestamp":
                return new ValueSource(expression, Kind.TIMESTAMP, null, List.of(), optional);
            default:
                break;
        }
        if (text.startsWith("header:")) {
            String name = text.substring("header:".length());
            if (name.isBlank()) {
                throw new IllegalArgumentException("source '%s': header name is missing".formatted(expression));
            }
            return new ValueSource(expression, Kind.HEADER, name, List.of(), optional);
        }
        if (text.startsWith("$")) {
            return new ValueSource(expression, Kind.JSON_PATH, null, parsePath(expression, text), optional);
        }
        throw new IllegalArgumentException(
                "source '%s' is not valid: expected key, value, topic, partition, offset, timestamp, header:<name>, a JSON path starting with '$', or const:<text>"
                        .formatted(expression));
    }

    private static List<Object> parsePath(String expression, String text) {
        List<Object> segments = new ArrayList<>();
        int i = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '.' && text.charAt(end) != '[') {
                    end++;
                }
                if (end == i + 1) {
                    throw new IllegalArgumentException("source '%s': empty name after '.'".formatted(expression));
                }
                segments.add(text.substring(i + 1, end));
                i = end;
            } else if (c == '[') {
                int close = text.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("source '%s': missing ']'".formatted(expression));
                }
                String inner = text.substring(i + 1, close);
                if (inner.length() >= 2
                        && (inner.charAt(0) == '\'' || inner.charAt(0) == '"')
                        && inner.charAt(inner.length() - 1) == inner.charAt(0)) {
                    segments.add(inner.substring(1, inner.length() - 1));
                } else {
                    try {
                        segments.add(Integer.valueOf(inner));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "source '%s': [%s] is neither an index nor a quoted name".formatted(expression, inner),
                                e);
                    }
                }
                i = close + 1;
            } else {
                throw new IllegalArgumentException(
                        "source '%s': unexpected '%s' at position %d".formatted(expression, c, i));
            }
        }
        return List.copyOf(segments);
    }

    /** Whether this source needs the value to be JSON. */
    public boolean requiresJson() {
        return kind == Kind.JSON_PATH;
    }

    public boolean isOptional() {
        return optional;
    }

    public String expression() {
        return expression;
    }

    /**
     * Evaluates the source.
     *
     * @return a {@code String}, {@code Long}, {@code Double} or {@code Boolean}; {@code null} for an absent optional
     *     value
     * @throws MappingException when a required value is absent or the value is not JSON where JSON is needed
     */
    public Object extract(InboundRecord record) {
        Object result = switch (kind) {
            case KEY -> record.key();
            case VALUE -> record.value();
            case TOPIC -> record.topic();
            case PARTITION -> Long.valueOf(record.partition());
            case OFFSET -> Long.valueOf(record.offset());
            case TIMESTAMP -> Long.valueOf(record.timestamp());
            case HEADER -> record.header(argument);
            case CONSTANT -> argument;
            case JSON_PATH -> fromJson(record);
        };
        if (result == null && !optional) {
            throw new MappingException(
                    "record %s: source '%s' yields no value".formatted(record.coordinates(), expression));
        }
        return result;
    }

    private Object fromJson(InboundRecord record) {
        JsonNode node = record.json();
        for (Object segment : path) {
            if (segment instanceof String name) {
                node = node.isObject() ? node.get(name) : null;
            } else {
                int index = (Integer) segment;
                node = node.isArray() ? node.get(index) : null;
            }
            if (node == null) {
                return null;
            }
        }
        return scalar(node);
    }

    private static Object scalar(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong()
                    ? (Object) node.longValue()
                    : node.bigIntegerValue().toString();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.toString();
    }

    @Override
    public String toString() {
        return expression;
    }
}

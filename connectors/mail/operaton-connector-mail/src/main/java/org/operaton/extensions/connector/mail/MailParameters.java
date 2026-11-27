/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Normalisation of connector input parameters, which arrive as whatever the BPMN expression produced. */
public final class MailParameters {

    private MailParameters() {}

    /** An address list parameter: a string (comma separated) or a collection of strings. */
    public static String addressList(String name, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank() ? null : text.toString();
        }
        if (value instanceof Collection<?> items) {
            String joined = items.stream().map(String::valueOf).collect(Collectors.joining(","));
            return joined.isBlank() ? null : joined;
        }
        throw new MailConnectorException("Parameter '" + name + "' must be a string or a list of strings, but was "
                + value.getClass().getName());
    }

    /** A string parameter. */
    public static String string(String name, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        throw new MailConnectorException("Parameter '" + name + "' must be a string, but was "
                + value.getClass().getName());
    }

    /** A list parameter: a collection or a single element. */
    public static List<Object> list(String name, Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> items) {
            return List.copyOf(items);
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        return List.of(value);
    }

    /** A boolean parameter: a Boolean or the strings {@code true}/{@code false}. */
    public static Boolean bool(String name, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof CharSequence text
                && ("true".equalsIgnoreCase(text.toString()) || "false".equalsIgnoreCase(text.toString()))) {
            return Boolean.valueOf(text.toString().toLowerCase(java.util.Locale.ROOT));
        }
        throw new MailConnectorException("Parameter '" + name + "' must be true or false, but was '" + value + "'");
    }

    /** An integer parameter: a Number or a numeric string. */
    public static Integer integer(String name, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new MailConnectorException("Parameter '" + name + "' must be a number, but was '" + value + "'", e);
        }
    }

    /** A map parameter with string keys. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(String name, Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new MailConnectorException("Parameter '" + name + "' must have string keys");
                }
            }
            return (Map<String, Object>) map;
        }
        throw new MailConnectorException("Parameter '" + name + "' must be a map, but was "
                + value.getClass().getName());
    }
}

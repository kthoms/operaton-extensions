/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer for the string results ({@code keysJson} and friends). Not API. */
final class Json {

    private Json() {}

    static String array(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, value);
        }
        return sb.append(']').toString();
    }

    static String object(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, entry.getKey());
            sb.append(':');
            value(sb, entry.getValue());
        }
        return sb.append('}').toString();
    }

    static String arrayOfObjects(List<? extends Map<String, ?>> objects) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, ?> object : objects) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(object(object));
        }
        return sb.append(']').toString();
    }

    private static void value(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            quote(sb, value.toString());
        }
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

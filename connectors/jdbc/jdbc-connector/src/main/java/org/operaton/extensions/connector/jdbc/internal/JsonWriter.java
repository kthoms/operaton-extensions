/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;

/**
 * Writes result values as JSON text without a JSON library, so that decimals are written digit for digit and never pass
 * through a double. Dates and times are ISO-8601 strings, binary data is Base64, UUIDs are strings. Stateless.
 */
public final class JsonWriter {

    private JsonWriter() {}

    /**
     * @param decimalsAsStrings write {@link BigDecimal} as JSON string (exact for every consumer) instead of a JSON
     *     number (exact in the text, but parsers that read numbers as double lose digits)
     */
    public static String write(Object value, boolean decimalsAsStrings) {
        StringBuilder out = new StringBuilder();
        append(out, value, decimalsAsStrings);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value, boolean decimalsAsStrings) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof BigDecimal d) {
            if (decimalsAsStrings) {
                string(out, d.toPlainString());
            } else {
                out.append(d.toPlainString());
            }
        } else if (value instanceof BigInteger || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JdbcConnectorException("Cannot write " + d + " as JSON number");
            }
            out.append(value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(e.getKey()));
                out.append(':');
                append(out, e.getValue(), decimalsAsStrings);
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, element, decimalsAsStrings);
            }
            out.append(']');
        } else if (value instanceof byte[] bytes) {
            string(out, Base64.getEncoder().encodeToString(bytes));
        } else if (value instanceof TemporalAccessor || value instanceof UUID || value instanceof CharSequence) {
            string(out, value.toString());
        } else {
            throw new JdbcConnectorException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

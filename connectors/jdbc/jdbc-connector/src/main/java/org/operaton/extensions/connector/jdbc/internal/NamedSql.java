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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;

/**
 * A SQL statement with named parameters ({@code :customerId}), parsed once into literal text and parameter slots.
 *
 * <p>The parser recognises string literals, quoted identifiers, comments, PostgreSQL dollar quoting and {@code ::}
 * casts, so a colon inside any of them is never a parameter. Values are never part of the SQL text: {@link #bind} emits
 * one {@code ?} per value and returns the values in order, to be set on a {@link java.sql.PreparedStatement}. That is
 * what makes SQL injection through parameter values impossible by construction.
 *
 * <p>The statement is rejected when it contains positional {@code ?} markers, a second statement, or an unterminated
 * literal or comment. Instances are immutable and thread-safe.
 */
public final class NamedSql {

    private sealed interface Part permits Text, Param {}

    private record Text(String text) implements Part {}

    private record Param(String name) implements Part {}

    /** The SQL with every named parameter replaced by {@code ?}, and the values to bind in order. */
    public record Bound(String sql, List<Object> values, List<String> names) {}

    private final List<Part> parts;
    private final Set<String> parameterNames;

    private NamedSql(List<Part> parts, Set<String> parameterNames) {
        this.parts = parts;
        this.parameterNames = parameterNames;
    }

    /** The distinct parameter names in order of first appearance. */
    public Set<String> parameterNames() {
        return parameterNames;
    }

    public static NamedSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new JdbcConnectorException("The SQL statement ('sql') is required and must not be blank");
        }
        List<Part> parts = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        int n = sql.length();
        int i = 0;
        boolean statementEnded = false;

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                requireOpen(statementEnded, sql, i);
                text.append(sql, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end + 1;
                text.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new JdbcConnectorException("Unterminated block comment in SQL at position " + i);
                }
                text.append(sql, i, end + 2);
                i = end + 2;
            } else if (c == '$' && isDollarQuoteStart(sql, i)) {
                int tagEnd = sql.indexOf('$', i + 1);
                String tag = sql.substring(i, tagEnd + 1);
                int end = sql.indexOf(tag, tagEnd + 1);
                if (end < 0) {
                    throw new JdbcConnectorException(
                            "Unterminated dollar-quoted string " + tag + " in SQL at position " + i);
                }
                requireOpen(statementEnded, sql, i);
                text.append(sql, i, end + tag.length());
                i = end + tag.length();
            } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                requireOpen(statementEnded, sql, i);
                text.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < n && isNameStart(sql.charAt(i + 1))) {
                requireOpen(statementEnded, sql, i);
                int end = i + 1;
                while (end < n && isNamePart(sql.charAt(end))) {
                    end++;
                }
                String name = sql.substring(i + 1, end);
                if (!text.isEmpty()) {
                    parts.add(new Text(text.toString()));
                    text.setLength(0);
                }
                parts.add(new Param(name));
                names.add(name);
                i = end;
            } else if (c == '?') {
                throw new JdbcConnectorException("Positional parameter marker '?' at position " + i
                        + " is not allowed; use named parameters (:name)");
            } else if (c == ';') {
                statementEnded = true;
                text.append(c);
                i++;
            } else {
                if (statementEnded && !Character.isWhitespace(c)) {
                    throw multipleStatements(i);
                }
                text.append(c);
                i++;
            }
        }
        if (!text.isEmpty()) {
            parts.add(new Text(text.toString()));
        }
        stripTrailingSemicolon(parts);
        return new NamedSql(List.copyOf(parts), Collections.unmodifiableSet(names));
    }

    /**
     * Replaces the parameters by {@code ?} placeholders. A {@link Collection} or array value expands to a comma
     * separated list of placeholders (for {@code IN (:ids)}); empty collections are rejected.
     *
     * @throws JdbcConnectorException if a parameter has no entry in {@code values} (a {@code null} value counts as
     *     bound)
     */
    public Bound bind(Map<String, Object> values) {
        List<String> missing = new ArrayList<>();
        for (String name : parameterNames) {
            if (!values.containsKey(name)) {
                missing.add(":" + name);
            }
        }
        if (!missing.isEmpty()) {
            throw new JdbcConnectorException("Unbound SQL parameter(s) " + missing + "; provided: " + values.keySet());
        }
        StringBuilder sql = new StringBuilder();
        List<Object> bound = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof Text t) {
                sql.append(t.text());
            } else {
                String name = ((Param) part).name();
                Object value = values.get(name);
                List<Object> expanded = expand(name, value);
                if (expanded == null) {
                    sql.append('?');
                    bound.add(value);
                    names.add(name);
                } else {
                    for (int k = 0; k < expanded.size(); k++) {
                        sql.append(k == 0 ? "?" : ", ?");
                        bound.add(expanded.get(k));
                        names.add(name);
                    }
                }
            }
        }
        return new Bound(sql.toString(), bound, names);
    }

    private static List<Object> expand(String name, Object value) {
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                throw new JdbcConnectorException(
                        "Parameter ':" + name + "' is an empty list; an empty IN list is invalid SQL");
            }
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
            int length = Array.getLength(value);
            if (length == 0) {
                throw new JdbcConnectorException(
                        "Parameter ':" + name + "' is an empty array; an empty IN list is invalid SQL");
            }
            List<Object> list = new ArrayList<>(length);
            for (int k = 0; k < length; k++) {
                list.add(Array.get(value, k));
            }
            return list;
        }
        return null;
    }

    private static void requireOpen(boolean statementEnded, String sql, int position) {
        if (statementEnded) {
            throw multipleStatements(position);
        }
    }

    private static JdbcConnectorException multipleStatements(int position) {
        return new JdbcConnectorException(
                "More than one SQL statement (content after ';' at position " + position + ") is not allowed");
    }

    private static void stripTrailingSemicolon(List<Part> parts) {
        for (int p = parts.size() - 1; p >= 0; p--) {
            if (!(parts.get(p) instanceof Text t)) {
                return;
            }
            String stripped = t.text().stripTrailing();
            if (stripped.isEmpty()) {
                parts.remove(p);
                continue;
            }
            if (stripped.endsWith(";")) {
                String without = stripped.substring(0, stripped.length() - 1).stripTrailing();
                if (without.isEmpty()) {
                    parts.remove(p);
                } else {
                    parts.set(p, new Text(without));
                }
            }
            return;
        }
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int n = sql.length();
        int i = start + 1;
        while (i < n) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new JdbcConnectorException(
                "Unterminated " + quote + "-quoted literal or identifier in SQL at position " + start);
    }

    private static boolean isDollarQuoteStart(String sql, int i) {
        if (i > 0 && (isNamePart(sql.charAt(i - 1)) || sql.charAt(i - 1) == '$')) {
            return false; // part of an identifier such as a$b
        }
        int j = i + 1;
        while (j < sql.length() && isNamePart(sql.charAt(j))) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '$' && (j == i + 1 || isNameStart(sql.charAt(i + 1)));
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

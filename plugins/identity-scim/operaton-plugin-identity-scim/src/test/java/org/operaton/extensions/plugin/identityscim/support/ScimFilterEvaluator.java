/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small SCIM filter parser and evaluator (RFC 7644, section 3.4.2.2) for the test server: and, or,
 * not, parentheses, value paths ({@code members[value eq "x"]}), dotted paths over multi-valued
 * attributes, and the operators eq, ne, co, sw, ew and pr. A syntax error throws, so a malformed
 * filter produced by the plugin fails the test.
 */
final class ScimFilterEvaluator {

    private final List<String> tokens = new ArrayList<>();
    private int position;

    private ScimFilterEvaluator(String filter) {
        tokenize(filter);
    }

    /** Parses the filter; throws {@link IllegalArgumentException} when it is malformed. */
    static Node parse(String filter) {
        ScimFilterEvaluator parser = new ScimFilterEvaluator(filter);
        Node node = parser.or();
        if (parser.position != parser.tokens.size()) {
            throw new IllegalArgumentException("Unexpected token '" + parser.tokens.get(parser.position) + "'");
        }
        return node;
    }

    /** A parsed filter. */
    interface Node {
        boolean test(JsonNode resource);
    }

    private Node or() {
        Node left = and();
        while (peekIs("or")) {
            position++;
            Node l = left;
            Node r = and();
            left = resource -> l.test(resource) || r.test(resource);
        }
        return left;
    }

    private Node and() {
        Node left = factor();
        while (peekIs("and")) {
            position++;
            Node l = left;
            Node r = factor();
            left = resource -> l.test(resource) && r.test(resource);
        }
        return left;
    }

    private Node factor() {
        if (peekIs("(")) {
            position++;
            Node inner = or();
            expect(")");
            return inner;
        }
        if (peekIs("not")) {
            position++;
            expect("(");
            Node inner = or();
            expect(")");
            return resource -> !inner.test(resource);
        }
        String path = next();
        if (peekIs("[")) {
            position++;
            Node inner = or();
            expect("]");
            return resource -> {
                for (JsonNode element : values(resource, path)) {
                    if (inner.test(element)) {
                        return true;
                    }
                }
                return false;
            };
        }
        String operator = next().toLowerCase(Locale.ROOT);
        if (operator.equals("pr")) {
            return resource -> values(resource, path).stream()
                    .anyMatch(v -> !v.isNull() && !(v.isTextual() && v.asText().isEmpty()));
        }
        String operand = next();
        boolean quoted = operand.startsWith("\"");
        String value = quoted ? unquote(operand) : operand;
        return switch (operator) {
            case "eq" -> resource -> values(resource, path).stream().anyMatch(v -> same(v, value, quoted));
            case "ne" -> resource -> values(resource, path).stream().noneMatch(v -> same(v, value, quoted));
            case "co" ->
                resource -> values(resource, path).stream()
                        .anyMatch(v -> v.isTextual() && lower(v.asText()).contains(lower(value)));
            case "sw" ->
                resource -> values(resource, path).stream()
                        .anyMatch(v -> v.isTextual() && lower(v.asText()).startsWith(lower(value)));
            case "ew" ->
                resource -> values(resource, path).stream()
                        .anyMatch(v -> v.isTextual() && lower(v.asText()).endsWith(lower(value)));
            default -> throw new IllegalArgumentException("Unsupported operator '" + operator + "'");
        };
    }

    private static boolean same(JsonNode node, String value, boolean quoted) {
        if (quoted) {
            return node.isTextual() && node.asText().equalsIgnoreCase(value);
        }
        return node.isValueNode() && node.asText().equalsIgnoreCase(value);
    }

    /** All values at the dotted path; multi-valued attributes on the way are flattened. */
    static List<JsonNode> values(JsonNode resource, String path) {
        List<JsonNode> current = List.of(resource);
        for (String segment : path.split("\\.")) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                JsonNode child = node.get(segment);
                if (child == null) {
                    continue;
                }
                if (child.isArray()) {
                    child.forEach(next::add);
                } else {
                    next.add(child);
                }
            }
            current = next;
        }
        return current;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private boolean peekIs(String token) {
        return position < tokens.size() && tokens.get(position).equalsIgnoreCase(token);
    }

    private void expect(String token) {
        if (!peekIs(token)) {
            throw new IllegalArgumentException("Expected '" + token + "' at token " + position);
        }
        position++;
    }

    private String next() {
        if (position >= tokens.size()) {
            throw new IllegalArgumentException("Unexpected end of filter");
        }
        return tokens.get(position++);
    }

    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void tokenize(String filter) {
        int i = 0;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if ("()[]".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '"') {
                int j = i + 1;
                while (j < filter.length() && filter.charAt(j) != '"') {
                    j += filter.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= filter.length()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                tokens.add(filter.substring(i, j + 1));
                i = j + 1;
            } else {
                int j = i;
                while (j < filter.length()
                        && !Character.isWhitespace(filter.charAt(j))
                        && "()[]\"".indexOf(filter.charAt(j)) < 0) {
                    j++;
                }
                tokens.add(filter.substring(i, j));
                i = j;
            }
        }
    }
}

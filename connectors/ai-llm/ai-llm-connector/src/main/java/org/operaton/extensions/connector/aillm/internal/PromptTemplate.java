/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.VariableSource;

/**
 * Renders <code>{{name}}</code> placeholders. A placeholder names a variable, optionally followed by
 * a path into maps and lists, for example <code>{{customer.address.city}}</code> or
 * <code>{{items.0.sku}}</code>. Values are rendered with {@link Values#render(Object)}. A placeholder
 * that cannot be resolved fails the call: a prompt with a silently empty gap produces a wrong answer,
 * not an error. A backslash before the braces writes them literally. Stateless and thread-safe.
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("(\\\\)?\\{\\{\\s*([A-Za-z_][\\w-]*(?:\\.[\\w-]+)*)\\s*}}");

    private PromptTemplate() {}

    public static String render(String template, VariableSource variables) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = matcher.group().substring(1);
            } else {
                replacement = Values.render(resolve(matcher.group(2), variables));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolve(String expression, VariableSource variables) {
        String[] parts = expression.split("\\.");
        if (!variables.has(parts[0])) {
            throw new AiLlmException(
                    "Prompt placeholder {{" + expression + "}}: variable '" + parts[0] + "' is not defined");
        }
        Object value = variables.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String key = parts[i];
            if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(key)) {
                    throw new AiLlmException("Prompt placeholder {{" + expression + "}}: no entry '" + key + "'");
                }
                value = map.get(key);
            } else if (value instanceof List<?> list) {
                int index = parseIndex(expression, key);
                if (index >= list.size()) {
                    throw new AiLlmException("Prompt placeholder {{" + expression + "}}: index " + index
                            + " is out of range (size " + list.size() + ")");
                }
                value = list.get(index);
            } else {
                throw new AiLlmException("Prompt placeholder {{" + expression + "}}: cannot navigate into '" + key
                        + "' of a "
                        + (value == null ? "null value" : value.getClass().getName())
                        + "; only maps and lists can be navigated");
            }
        }
        return value;
    }

    private static int parseIndex(String expression, String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new AiLlmException("Prompt placeholder {{" + expression + "}}: '" + key + "' is not a list index", e);
        }
    }
}

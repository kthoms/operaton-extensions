/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.operaton.extensions.connector.mail.MailConnectorException;

/**
 * Replaces placeholders such as <code>${name}</code>, <code>${a.b.c}</code> (nested maps) and <code>{{name}}</code>
 * with the variable's string value. Inside a BPMN model the engine evaluates <code>${...}</code> itself before the
 * connector sees it, so use the <code>{{name}}</code> form there. A placeholder without a value fails the rendering; there is no escape syntax. In HTML
 * templates the values are HTML-escaped.
 */
final class SimpleTemplateRenderer implements TemplateRenderer {

    static final SimpleTemplateRenderer INSTANCE = new SimpleTemplateRenderer();

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}|\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private SimpleTemplateRenderer() {}

    @Override
    public String render(String template, Map<String, Object> variables, boolean html) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Object value = resolve(variables, path);
            if (value == null) {
                throw new MailConnectorException("Template placeholder ${" + path + "} has no value");
            }
            String text = value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(html ? escape(text) : text));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolve(Map<String, Object> variables, String path) {
        Object current = variables;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}

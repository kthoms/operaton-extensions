/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.template;

import java.util.Map;

/** Renders a mail template with variables. Implementations are thread-safe. */
public interface TemplateRenderer {

    /**
     * @param template the template source
     * @param variables the values available to the template
     * @param html whether the result is HTML: values are escaped
     * @throws org.operaton.extensions.connector.mail.MailConnectorException if the template cannot be rendered
     */
    String render(String template, Map<String, Object> variables, boolean html);

    /** The renderer for {@code engine}: {@code simple} (default when {@code null}) or {@code freemarker}. */
    static TemplateRenderer forEngine(String engine) {
        String name =
                engine == null || engine.isBlank() ? "simple" : engine.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "simple" -> SimpleTemplateRenderer.INSTANCE;
            case "freemarker" -> FreemarkerTemplateRenderer.create();
            default ->
                throw new org.operaton.extensions.connector.mail.MailConnectorException(
                        "Unknown templateEngine '" + engine + "': use 'simple' or 'freemarker'");
        };
    }
}

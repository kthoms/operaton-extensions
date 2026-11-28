/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.template;

import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import org.operaton.extensions.connector.mail.MailConnectorException;

/**
 * Renders with Apache FreeMarker, which must be on the classpath (an optional dependency of this connector). HTML
 * templates are auto-escaped. {@code ?new} and the {@code ?api} built-in are disabled.
 */
final class FreemarkerTemplateRenderer implements TemplateRenderer {

    private final Configuration configuration;

    private FreemarkerTemplateRenderer(Configuration configuration) {
        this.configuration = configuration;
    }

    static TemplateRenderer create() {
        try {
            Class.forName("freemarker.template.Configuration");
        } catch (ClassNotFoundException e) {
            throw new MailConnectorException(
                    "templateEngine 'freemarker' needs org.freemarker:freemarker on the classpath", e);
        }
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        configuration.setAPIBuiltinEnabled(false);
        return new FreemarkerTemplateRenderer(configuration);
    }

    @Override
    public String render(String template, Map<String, Object> variables, boolean html) {
        try {
            // the .ftlh extension switches on HTML auto-escaping
            Template parsed = new Template(html ? "mail.ftlh" : "mail.ftl", new StringReader(template), configuration);
            StringWriter out = new StringWriter();
            parsed.process(variables, out);
            return out.toString();
        } catch (IOException | freemarker.template.TemplateException e) {
            throw new MailConnectorException("Failed to render the FreeMarker template: " + e.getMessage(), e);
        }
    }
}

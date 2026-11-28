/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.mail.MailConnectorException;

class TemplateRendererTest {

    private final TemplateRenderer simple = TemplateRenderer.forEngine(null);
    private final TemplateRenderer freemarker = TemplateRenderer.forEngine("freemarker");

    @Test
    void simpleReplacesPlaceholdersAndNestedMaps() {
        String result = simple.render(
                "Hello ${ name }, ticket {{ticket.id}} and {{ name }} ($1.00 \\ done)",
                Map.of("name", "Ada", "ticket", Map.of("id", 42)),
                false);

        assertThat(result).isEqualTo("Hello Ada, ticket 42 and Ada ($1.00 \\ done)");
    }

    @Test
    void simpleFailsOnMissingValue() {
        assertThatThrownBy(() -> simple.render("Hi ${who}", Map.of(), false))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("${who}");
        assertThatThrownBy(() -> simple.render("Hi ${a.b}", Map.of("a", "x"), false))
                .isInstanceOf(MailConnectorException.class);
    }

    @Test
    void simpleEscapesValuesInHtml() {
        String result = simple.render("<p>${text}</p>", Map.of("text", "<script>\"x\" & 'y'</script>"), true);

        assertThat(result).isEqualTo("<p>&lt;script&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/script&gt;</p>");
    }

    @Test
    void freemarkerRendersAndEscapesHtml() {
        assertThat(freemarker.render("Hello ${name}, <#list items as i>${i}<#sep>, </#list>", items(), false))
                .isEqualTo("Hello <b>, 1, 2");
        assertThat(freemarker.render("<p>${name}</p>", items(), true)).isEqualTo("<p>&lt;b&gt;</p>");
    }

    @Test
    void freemarkerFailsLoudlyOnMissingValueAndForbiddenBuiltins() {
        assertThatThrownBy(() -> freemarker.render("${missing}", Map.of(), false))
                .isInstanceOf(MailConnectorException.class);
        assertThatThrownBy(() -> freemarker.render(
                        "<#assign x=\"freemarker.template.utility.Execute\"?new()>${x(\"id\")}", Map.of(), false))
                .isInstanceOf(MailConnectorException.class);
    }

    @Test
    void unknownEngineFails() {
        assertThatThrownBy(() -> TemplateRenderer.forEngine("velocity"))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("velocity");
    }

    private static Map<String, Object> items() {
        return Map.of("name", "<b>", "items", java.util.List.of(1, 2));
    }
}

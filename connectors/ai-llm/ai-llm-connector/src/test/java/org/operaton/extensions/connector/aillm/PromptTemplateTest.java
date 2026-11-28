/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.aillm.internal.PromptTemplate;

class PromptTemplateTest {

    final VariableSource variables = VariableSource.of(Map.of(
            "name",
            "Alice",
            "count",
            3,
            "order",
            Map.of("items", List.of(Map.of("sku", "A-1")), "paid", true),
            "tags",
            List.of("x", "y")));

    @Test
    void rendersVariablesPathsAndStructuredValues() {
        assertThat(PromptTemplate.render(
                        "{{ name }} has {{count}} items, first {{order.items.0.sku}}, paid {{order.paid}}, tags {{tags}}",
                        variables))
                .isEqualTo("Alice has 3 items, first A-1, paid true, tags [\"x\",\"y\"]");
    }

    @Test
    void escapedBracesStayLiteral() {
        assertThat(PromptTemplate.render("Use \\{{name}} as {{name}}", variables))
                .isEqualTo("Use {{name}} as Alice");
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        assertThat(PromptTemplate.render("Price: $5 {not a placeholder}", variables))
                .isEqualTo("Price: $5 {not a placeholder}");
    }

    @Test
    void unresolvablePlaceholdersFail() {
        assertThatThrownBy(() -> PromptTemplate.render("{{missing}}", variables))
                .hasMessageContaining("'missing'");
        assertThatThrownBy(() -> PromptTemplate.render("{{order.total}}", variables))
                .hasMessageContaining("no entry 'total'");
        assertThatThrownBy(() -> PromptTemplate.render("{{tags.5}}", variables)).hasMessageContaining("out of range");
        assertThatThrownBy(() -> PromptTemplate.render("{{name.first}}", variables))
                .hasMessageContaining("cannot navigate");
    }
}

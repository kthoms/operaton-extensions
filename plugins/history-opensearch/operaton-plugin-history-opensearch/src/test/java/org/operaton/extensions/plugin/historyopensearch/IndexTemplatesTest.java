/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.historyopensearch.internal.IndexTemplates;

class IndexTemplatesTest {

    private static Map<String, ? extends JsonNode> templates(
            java.util.function.Consumer<HistoryOpenSearchPlugin> customizer) {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        customizer.accept(plugin);
        return new IndexTemplates(new ObjectMapper(), plugin.validate()).all();
    }

    @Test
    void oneTemplatePerKindWithPatternPriorityAndMappings() {
        Map<String, ? extends JsonNode> templates = templates(p -> p.setIndexPrefix("acme"));

        assertThat(templates.keySet())
                .containsExactly(
                        "acme-process-instances",
                        "acme-activity-instances",
                        "acme-tasks",
                        "acme-variables",
                        "acme-incidents");
        JsonNode tasks = templates.get("acme-tasks");
        assertThat(tasks.path("index_patterns"))
                .extracting(JsonNode::asText)
                .containsExactly("acme-tasks", "acme-tasks-*");
        assertThat(tasks.path("priority").asInt()).isEqualTo(100);
        JsonNode properties = tasks.path("template").path("mappings").path("properties");
        assertThat(properties.path("taskId").path("type").asText()).isEqualTo("keyword");
        assertThat(properties.path("startTime").path("type").asText()).isEqualTo("date");
        assertThat(properties.path("name").path("type").asText()).isEqualTo("text");
        assertThat(properties
                        .path("name")
                        .path("fields")
                        .path("raw")
                        .path("type")
                        .asText())
                .isEqualTo("keyword");
        assertThat(properties.path("priority").path("type").asText()).isEqualTo("integer");
        // unknown strings (properties of later Operaton versions) become keywords
        assertThat(tasks.path("template")
                        .path("mappings")
                        .path("dynamic_templates")
                        .get(0)
                        .path("strings_as_keywords")
                        .path("mapping")
                        .path("type")
                        .asText())
                .isEqualTo("keyword");
        JsonNode variables = templates
                .get("acme-variables")
                .path("template")
                .path("mappings")
                .path("properties");
        assertThat(variables.path("valueLong").path("type").asText()).isEqualTo("long");
        assertThat(variables.path("valueBoolean").path("type").asText()).isEqualTo("boolean");
        assertThat(variables.path("valueDate").path("type").asText()).isEqualTo("date");
    }

    @Test
    void shardsAndReplicasAreOnlySetWhenConfigured() {
        JsonNode untouched =
                templates(p -> {}).get("operaton-tasks").path("template").path("settings");
        JsonNode configured = templates(p -> {
                    p.setNumberOfShards(3);
                    p.setNumberOfReplicas(0);
                })
                .get("operaton-tasks")
                .path("template")
                .path("settings");

        assertThat(untouched.size()).isZero();
        assertThat(configured.path("index.number_of_shards").asInt()).isEqualTo(3);
        assertThat(configured.path("index.number_of_replicas").asInt()).isZero();
    }

    @Test
    void withoutDatePartTheTemplateMatchesTheSingleIndex() {
        JsonNode template = templates(p -> p.setIndexDatePattern("")).get("operaton-incidents");

        assertThat(template.path("index_patterns")).extracting(JsonNode::asText).containsExactly("operaton-incidents");
    }

    @Test
    void theSameOptionsGiveTheSameTemplatesSoInstallingThemAgainIsIdempotent() {
        assertThat(templates(p -> {})).isEqualTo(templates(p -> {}));
    }
}

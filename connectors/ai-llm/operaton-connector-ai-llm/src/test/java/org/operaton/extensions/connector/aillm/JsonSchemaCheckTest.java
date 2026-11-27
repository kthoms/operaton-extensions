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
import org.operaton.extensions.connector.aillm.internal.JsonSchemaCheck;

class JsonSchemaCheckTest {

    final JsonSchemaCheck check = JsonSchemaCheck.compile(
            "{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1}}}");

    @Test
    void acceptsAValidDocumentAlsoInACodeFence() {
        assertThat(check.check("{\"n\": 2}").value()).isEqualTo(Map.of("n", 2));
        assertThat(check.check("```json\n{\"n\":3}\n```").json()).isEqualTo("{\"n\":3}");
    }

    @Test
    void reportsWhatIsWrong() {
        assertThat(check.check("").problems()).containsExactly("the answer was empty");
        assertThat(check.check("n=2").problems().get(0)).startsWith("the answer is not valid JSON");
        List<String> problems = check.check("{\"n\":0}").problems();
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("minimum");
        assertThat(check.check("{}").problems().get(0)).contains("required");
    }

    @Test
    void honoursTheDeclaredDraft() {
        JsonSchemaCheck draft7 = JsonSchemaCheck.compile(
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertThat(draft7.check("[\"a\"]").valid()).isTrue();
        assertThat(draft7.check("[1]").valid()).isFalse();
    }

    @Test
    void refusesRemoteReferencesAndNonObjects() {
        assertThatThrownBy(() -> JsonSchemaCheck.compile("{\"$ref\":\"https://example.org/s.json\"}"))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("https://example.org/s.json");
        assertThatThrownBy(() -> JsonSchemaCheck.compile("[1]")).hasMessageContaining("must be a JSON object");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class MessageRendererTest {

    static IncidentInfo incident(String id, String message) {
        return new IncidentInfo(
                id,
                "failedJob",
                message,
                "charge",
                "pay:1:abc",
                "pay",
                "Payment",
                "pi-" + id,
                "ex-1",
                null,
                null,
                Instant.parse("2026-01-02T03:04:05Z"),
                "http://c/pi-" + id,
                null,
                null);
    }

    static MessageRenderer renderer(int max) {
        return new MessageRenderer(
                MessageRenderer.DEFAULT_SUBJECT,
                MessageRenderer.DEFAULT_BODY_TEMPLATE,
                MessageRenderer.DEFAULT_INCIDENT_TEMPLATE,
                max,
                ZoneId.of("UTC"));
    }

    @Test
    void rendersSubjectAndBody() {
        IncidentDigest digest = new IncidentDigest("pay", List.of(incident("1", "boom")), 0);

        assertThat(renderer(5).subject(digest)).isEqualTo("[Operaton] 1 incident(s) in process pay");
        String body = renderer(5).body(digest, UnaryOperator.identity());
        assertThat(body)
                .contains("1 incident(s) occurred in process Payment (pay):")
                .contains("Message: boom")
                .contains("Business key: -")
                .contains("Time: 2026-01-02 03:04:05 UTC")
                .contains("Cockpit: http://c/pi-1");
    }

    @Test
    void valuesAreNotInterpretedAsPlaceholders() {
        IncidentDigest digest = new IncidentDigest("pay", List.of(incident("1", "see @URL and @INCIDENTS")), 0);

        assertThat(renderer(5).body(digest, UnaryOperator.identity())).contains("Message: see @URL and @INCIDENTS");
    }

    @Test
    void summarisesIncidentsBeyondTheLimit() {
        List<IncidentInfo> incidents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            incidents.add(incident(String.valueOf(i), "m"));
        }
        IncidentDigest digest = new IncidentDigest("pay", incidents, 3);

        String body = renderer(2).body(digest, UnaryOperator.identity());

        assertThat(body).contains("... and 6 more incident(s) not shown.").contains("8 incident(s) occurred");
        assertThat(body).contains("pi-1").doesNotContain("pi-2");
    }

    @Test
    void escapeFunctionIsAppliedToValues() {
        IncidentDigest digest = new IncidentDigest("pay", List.of(incident("1", "a<b")), 0);

        assertThat(renderer(5).body(digest, v -> v.replace("<", "&lt;"))).contains("Message: a&lt;b");
    }

    @Test
    void rejectsUnknownPlaceholders() {
        assertThatThrownBy(() -> new MessageRenderer(
                        "s @COUNT", MessageRenderer.DEFAULT_BODY_TEMPLATE, "x @NOPE", 5, ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@NOPE")
                .hasMessageContaining("incidentTemplate");
    }
}

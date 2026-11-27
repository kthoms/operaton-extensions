/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageCalculator;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageEvent;
import org.operaton.extensions.plugin.processtestcoverage.model.ElementSource;
import org.operaton.extensions.plugin.processtestcoverage.model.ProcessModel;

class JsonTest {

    private static final ProcessModel MODEL =
            new ProcessModel("p", "p:1", 1, null, "<x/>", List.of("a", "b"), List.of("f"));

    private static CoverageEvent node(String id, String key) {
        return new CoverageEvent(ElementSource.FLOW_NODE, id, "task", key, java.time.Instant.EPOCH);
    }

    @Test
    void calculatorCountsDistinctKnownElementsOfTouchedModelsOnly() {
        var events = List.of(node("a", "p"), node("a", "p"), node("zzz", "p"), node("q", "other"));

        var result = CoverageCalculator.calculate(events, List.of(MODEL));

        assertThat(result.models()).hasSize(1);
        assertThat(result.covered()).isEqualTo(1);
        assertThat(result.ratio()).isEqualTo(1.0 / 3);
    }

    @Test
    void emptyResultHasNoRatio() {
        var result = CoverageCalculator.calculate(List.of(), List.of(MODEL));

        assertThat(result.isEmpty()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(result::ratio).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportDataEscapesXmlAndScriptEndTags() throws Exception {
        var model = new ProcessModel("p", "p:1", 1, null, "<a>\"q\"</script></a>", List.of("a"), List.of());
        var suite = new org.operaton.extensions.plugin.processtestcoverage.model.TestSuite("s", "S");
        var dir = java.nio.file.Files.createTempDirectory("ptc");

        new org.operaton.extensions.plugin.processtestcoverage.report.CoverageReportWriter("u", "c")
                .write(dir, suite, List.of(model));

        String html = java.nio.file.Files.readString(dir.resolve("index.html"));
        assertThat(html).contains("<\\/script>").contains("\\\"q\\\"");
        assertThat(java.nio.file.Files.readString(dir.resolve("coverage.json")))
                .contains("\"bpmn\":\"<a>\\\"q\\\"</script></a>\"");
        assertThat(Map.of()).isEmpty();
    }
}

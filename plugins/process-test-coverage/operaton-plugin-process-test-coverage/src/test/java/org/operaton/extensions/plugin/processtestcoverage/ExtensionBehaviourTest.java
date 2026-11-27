/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.operaton.extensions.plugin.processtestcoverage.fixtures.InstanceFieldFixtureAccess;

/** Runs fixture test classes through the JUnit platform and checks how the extension judges them. */
class ExtensionBehaviourTest {

    @TempDir
    Path reportDir;

    @BeforeEach
    void setReportDir() {
        System.setProperty("ptc.fixture.dir", reportDir.toString());
    }

    @AfterEach
    void clearReportDir() {
        System.clearProperty("ptc.fixture.dir");
    }

    private EngineExecutionResults run(String fixtureName) throws ClassNotFoundException {
        Class<?> fixture = Class.forName(InstanceFieldFixtureAccess.PACKAGE + "." + fixtureName);
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(fixture))
                .execute();
    }

    private static String failureMessages(EngineExecutionResults results) {
        StringBuilder out = new StringBuilder();
        results.allEvents().failed().stream()
                .forEach(e -> e.getRequiredPayload(org.junit.platform.engine.TestExecutionResult.class)
                        .getThrowable()
                        .ifPresent(t -> out.append(t.getMessage()).append('\n')));
        return out.toString();
    }

    @Test
    void thresholdMetPassesAndWritesHtmlAndJson() throws Exception {
        EngineExecutionResults results = run("ThresholdMetFixture");

        assertThat(failureMessages(results)).isEmpty();
        results.containerEvents().assertStatistics(s -> s.failed(0));
        Path dir = reportDir.resolve(InstanceFieldFixtureAccess.PACKAGE + ".ThresholdMetFixture");
        String json = Files.readString(dir.resolve("coverage.json"));
        assertThat(json).contains("\"ptc-gateway\"", "\"ratio\":0.5555555555555556");
        String html = Files.readString(dir.resolve("index.html"));
        assertThat(html).contains("bpmn-js@", "ptc-gateway", "\"displayName\":\"small()\"");
        assertThat(html).doesNotContain("/*__DATA__*/");
    }

    @Test
    void classThresholdMissedFailsTheClassButStillWritesTheReport() throws Exception {
        EngineExecutionResults results = run("ThresholdMissedFixture");

        assertThat(failureMessages(results))
                .contains("test class")
                .contains("55.6%")
                .contains("at least 90.0% is required")
                .contains("ptc-gateway: 55.6% (5 of 9 elements)");
        assertThat(reportDir.resolve(InstanceFieldFixtureAccess.PACKAGE + ".ThresholdMissedFixture/index.html"))
                .exists();
    }

    @Test
    void methodThresholdMissedFailsTheMethod() throws Exception {
        EngineExecutionResults results = run("MethodThresholdMissedFixture");

        assertThat(failureMessages(results)).contains("#small").contains("at least 90.0% is required");
    }

    @Test
    void minimumWithoutAnyExecutedProcessFails() throws Exception {
        EngineExecutionResults results = run("NoProcessFixture");

        assertThat(failureMessages(results)).contains("executed no process element");
    }

    @Test
    void registrationInAnInstanceFieldIsRejectedLoudly() throws Exception {
        EngineExecutionResults results = run("InstanceFieldFixture");

        assertThat(failureMessages(results)).contains("static @RegisterExtension");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageCalculator;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageResult;
import org.operaton.extensions.plugin.processtestcoverage.model.ModelCoverage;

/** Engine tests of what the plugin records, with the extension active but without assertions or reports. */
class RecordingTest {

    @RegisterExtension
    static ProcessTestCoverageExtension coverage =
            ProcessTestCoverageExtension.newBuilder().withoutReport().build();

    RuntimeService runtimeService;
    TaskService taskService;

    private ModelCoverage coverageOf(String key) {
        CoverageCollector collector = coverage.getCoverageCollector();
        CoverageResult result =
                CoverageCalculator.calculate(collector.activeRun().events(), collector.models());
        return result.models().stream()
                .filter(m -> m.processKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @Deployment(resources = "processes/ptc-gateway.bpmn")
    void exclusiveGatewayCoversOnlyTheTakenBranch() {
        runtimeService.startProcessInstanceByKey("ptc-gateway", Map.of("big", false));

        ModelCoverage mc = coverageOf("ptc-gateway");
        assertThat(mc.coveredFlowNodeIds()).containsExactlyInAnyOrder("start", "gw", "endSmall");
        assertThat(mc.coveredSequenceFlowIds()).containsExactlyInAnyOrder("f1", "fSmall");
        assertThat(mc.total()).isEqualTo(9);
        assertThat(mc.ratio()).isEqualTo(5.0 / 9);
    }

    @Test
    @Deployment(resources = "processes/ptc-gateway.bpmn")
    void waitStateIsCoveredWhenReachedAndItsOutgoingFlowOnceTheTaskIsCompleted() {
        runtimeService.startProcessInstanceByKey("ptc-gateway", Map.of("big", true));
        assertThat(coverageOf("ptc-gateway").coveredFlowNodeIds())
                .contains("review")
                .doesNotContain("endBig");

        taskService.complete(taskService.createTaskQuery().singleResult().getId());

        ModelCoverage mc = coverageOf("ptc-gateway");
        assertThat(mc.coveredFlowNodeIds()).contains("review", "endBig");
        assertThat(mc.coveredSequenceFlowIds()).containsExactlyInAnyOrder("f1", "fBig", "f2");
    }

    @Test
    @Deployment(resources = "processes/ptc-events.bpmn")
    void flowAfterEventBasedGatewayIsCoveredWhenItsEventFires() {
        runtimeService.startProcessInstanceByKey("ptc-events");
        runtimeService.messageEventReceived(
                "go2",
                runtimeService
                        .createExecutionQuery()
                        .messageEventSubscriptionName("go2")
                        .singleResult()
                        .getId());

        ModelCoverage mc = coverageOf("ptc-events");
        assertThat(mc.coveredSequenceFlowIds()).contains("fRecv", "f3").doesNotContain("fMsg");
        assertThat(mc.coveredFlowNodeIds()).contains("recv", "end2").doesNotContain("catchMsg");
    }

    @Test
    @Deployment(resources = "processes/ptc-link.bpmn")
    void linkThrowAndCatchEventsAreCovered() {
        runtimeService.startProcessInstanceByKey("ptc-link");

        ModelCoverage mc = coverageOf("ptc-link");
        assertThat(mc.coveredFlowNodeIds()).containsExactlyInAnyOrder("start", "throwLink", "catchLink", "end");
        assertThat(mc.ratio()).isEqualTo(1.0);
    }

    @Test
    @Deployment(resources = "processes/ptc-compensation.bpmn")
    void compensationBoundaryEventIsCovered() {
        runtimeService.startProcessInstanceByKey("ptc-compensation");

        ModelCoverage mc = coverageOf("ptc-compensation");
        assertThat(mc.coveredFlowNodeIds()).contains("book", "compBoundary", "cancel", "undo", "end");
    }

    @Test
    @Deployment(resources = {"processes/ptc-parent.bpmn", "processes/ptc-gateway.bpmn"})
    void calledProcessesGetTheirOwnModel() {
        runtimeService.startProcessInstanceByKey("ptc-parent", Map.of("big", false));

        assertThat(coverage.getCoverageCollector().models())
                .extracting(m -> m.key())
                .contains("ptc-parent", "ptc-gateway");
        assertThat(coverageOf("ptc-parent").coveredFlowNodeIds()).containsExactlyInAnyOrder("start", "call", "end");
        assertThat(coverageOf("ptc-gateway").coveredFlowNodeIds()).contains("endSmall");
    }

    @Test
    @Deployment(resources = "processes/ptc-multi.bpmn")
    void multiInstanceBodyCountsOnce() {
        runtimeService.startProcessInstanceByKey("ptc-multi");

        ModelCoverage mc = coverageOf("ptc-multi");
        assertThat(mc.ratio()).isEqualTo(1.0);
        assertThat(mc.coveredFlowNodeIds()).contains("each", "subStart", "work", "subEnd");
    }

    @Test
    @Deployment(resources = "processes/ptc-gateway.bpmn")
    void excludedKeysAreNotRecorded() {
        coverage.getCoverageCollector().setExcludedProcessKeys(Set.of("ptc-gateway"));
        try {
            runtimeService.startProcessInstanceByKey("ptc-gateway", Map.of("big", false));
            assertThat(coverage.getCoverageCollector().activeRun().events()).isEmpty();
        } finally {
            coverage.getCoverageCollector().setExcludedProcessKeys(Set.of());
        }
    }
}

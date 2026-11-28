/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.extensions.plugin.processtestcoverage.ProcessTestCoverageExtension;

/**
 * Tests of the invoice approval process that must together execute every element of the model. If a
 * path loses its test, the class fails with the coverage that is left; the report shows which
 * elements nobody executes: {@code target/process-test-coverage/<this class>/index.html}.
 */
class InvoiceApprovalCoverageIT {

    @RegisterExtension
    static ProcessTestCoverageExtension coverage = ProcessTestCoverageExtension.newBuilder()
            .withMinimumCoverage(1.0)
            .withMinimumMethodCoverage(0.5)
            .build();

    RuntimeService runtimeService;
    TaskService taskService;
    HistoryService historyService;

    @Test
    @Deployment(resources = "bpmn/invoice-approval.bpmn")
    void smallInvoiceIsBookedWithoutApproval() {
        ProcessInstance invoice = runtimeService.startProcessInstanceByKey("invoice-approval", Map.of("amount", 250));

        assertThat(historyService
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(invoice.getId())
                        .variableName("bookingReference")
                        .count())
                .isEqualTo(1);
        assertThat(runtimeService
                        .createProcessInstanceQuery()
                        .processInstanceId(invoice.getId())
                        .count())
                .isZero();
    }

    @Test
    @Deployment(resources = "bpmn/invoice-approval.bpmn")
    void largeInvoiceIsBookedAfterApproval() {
        ProcessInstance invoice = runtimeService.startProcessInstanceByKey("invoice-approval", Map.of("amount", 5000));
        String taskId = taskService
                .createTaskQuery()
                .processInstanceId(invoice.getId())
                .singleResult()
                .getId();

        taskService.complete(taskId, Map.of("approved", true));

        assertThat(runtimeService
                        .createProcessInstanceQuery()
                        .processInstanceId(invoice.getId())
                        .count())
                .isZero();
    }

    @Test
    @Deployment(resources = "bpmn/invoice-approval.bpmn")
    void largeInvoiceIsRejected() {
        ProcessInstance invoice = runtimeService.startProcessInstanceByKey("invoice-approval", Map.of("amount", 5000));
        String taskId = taskService
                .createTaskQuery()
                .processInstanceId(invoice.getId())
                .singleResult()
                .getId();

        taskService.complete(taskId, Map.of("approved", false));

        assertThat(runtimeService
                        .createProcessInstanceQuery()
                        .processInstanceId(invoice.getId())
                        .count())
                .isZero();
    }
}

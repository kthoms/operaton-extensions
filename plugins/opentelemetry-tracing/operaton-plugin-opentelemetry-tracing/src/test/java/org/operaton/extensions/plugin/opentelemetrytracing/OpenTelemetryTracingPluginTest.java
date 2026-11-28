/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

class OpenTelemetryTracingPluginTest {

    private final TracingTestSupport otel = new TracingTestSupport();
    private final OpenTelemetryTracingPlugin plugin = new OpenTelemetryTracingPlugin();

    @RegisterExtension
    ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterEachTest()
            .configurator(cfg -> {
                plugin.setOpenTelemetry(otel.sdk);
                plugin.setTraceGateways(true);
                cfg.getProcessEnginePlugins().add(plugin);
                cfg.setJobExecutorActivate(false);
            })
            .build();

    @AfterEach
    void tearDown() {
        engine.getRepositoryService()
                .createDeploymentQuery()
                .list()
                .forEach(d -> engine.getRepositoryService().deleteDeployment(d.getId(), true));
        otel.close();
    }

    private static org.operaton.bpm.model.bpmn.builder.ProcessBuilder process(String id) {
        return Bpmn.createExecutableProcess(id).operatonHistoryTimeToLive(1);
    }

    private void deploy(BpmnModelInstance model) {
        TracingTestSupport.deploy(engine.getProcessEngine(), model);
    }

    @Test
    void processAndActivitiesFormOneTrace() {
        deploy(process("simple")
                .startEvent("start")
                .serviceTask("work")
                .operatonClass(TracingTestSupport.Noop.class.getName())
                .endEvent("end")
                .done());

        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("simple", "BK-1");

        SpanData process = otel.single("process simple");
        assertThat(process.getParentSpanContext().isValid()).isFalse();
        assertThat(process.getAttributes().get(OperatonTracingAttributes.PROCESS_INSTANCE_ID))
                .isEqualTo(pi.getId());
        assertThat(process.getAttributes().get(OperatonTracingAttributes.PROCESS_DEFINITION_KEY))
                .isEqualTo("simple");
        assertThat(process.getAttributes().get(OperatonTracingAttributes.BUSINESS_KEY))
                .isEqualTo("BK-1");
        SpanData work = otel.single("serviceTask work");
        assertThat(work.getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(work.getTraceId()).isEqualTo(process.getTraceId());
        assertThat(work.getAttributes().get(OperatonTracingAttributes.ACTIVITY_ID))
                .isEqualTo("work");
        assertThat(work.getAttributes().get(OperatonTracingAttributes.ACTIVITY_TYPE))
                .isEqualTo("serviceTask");
        assertThat(otel.single("startEvent start").getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(otel.single("noneEndEvent end").getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(plugin.getOpenSpanCount()).isZero();
    }

    @Test
    void traceparentIsStoredInTheProcessVariable() {
        deploy(process("waiting").startEvent().userTask("approve").endEvent().done());

        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("waiting");

        String traceparent = (String) engine.getRuntimeService().getVariable(pi.getId(), "_traceparent");
        assertThat(plugin.getOpenSpanCount()).isEqualTo(2); // process and user task
        engine.getTaskService()
                .createTaskQuery()
                .list()
                .forEach(t -> engine.getTaskService().complete(t.getId()));
        SpanData process = otel.single("process waiting");
        assertThat(traceparent).startsWith("00-" + process.getTraceId() + "-" + process.getSpanId() + "-");
        assertThat(plugin.getOpenSpanCount()).isZero();
    }

    @Test
    void incomingContextIsTheParentOfTheProcessSpan() {
        deploy(process("incoming").startEvent().endEvent().done());
        Span caller = otel.tracer().spanBuilder("http request").startSpan();
        try (Scope ignored = caller.makeCurrent()) {
            engine.getRuntimeService().startProcessInstanceByKey("incoming");
        } finally {
            caller.end();
        }

        SpanData process = otel.single("process incoming");
        assertThat(process.getParentSpanId()).isEqualTo(caller.getSpanContext().getSpanId());
        assertThat(process.getTraceId()).isEqualTo(caller.getSpanContext().getTraceId());
    }

    @Test
    void asyncContinuationJobContinuesTheTrace() {
        deploy(process("async")
                .startEvent()
                .serviceTask("work")
                .operatonAsyncBefore()
                .operatonClass(TracingTestSupport.Noop.class.getName())
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("async");
        Job job = engine.getManagementService().createJobQuery().singleResult();

        engine.getManagementService().executeJob(job.getId());

        SpanData process = otel.single("process async");
        SpanData jobSpan = otel.single("job async-continuation");
        SpanData work = otel.single("serviceTask work");
        assertThat(jobSpan.getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(jobSpan.getAttributes().get(OperatonTracingAttributes.JOB_ID))
                .isEqualTo(job.getId());
        assertThat(jobSpan.getAttributes().get(OperatonTracingAttributes.JOB_TYPE))
                .isEqualTo("async-continuation");
        assertThat(work.getParentSpanId()).isEqualTo(jobSpan.getSpanId());
        assertThat(work.getTraceId()).isEqualTo(process.getTraceId());
        assertThat(plugin.getOpenSpanCount()).isZero();
    }

    @Test
    void failingJobEndsItsSpansWithAnError() {
        deploy(process("failing")
                .startEvent()
                .serviceTask("work")
                .operatonAsyncBefore()
                .operatonClass(TracingTestSupport.Fail.class.getName())
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("failing");
        Job job = engine.getManagementService().createJobQuery().singleResult();

        assertThatThrownBy(() -> engine.getManagementService().executeJob(job.getId()))
                .hasStackTraceContaining("boom");

        SpanData jobSpan = otel.single("job async-continuation");
        assertThat(jobSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(jobSpan.getEvents()).anyMatch(e -> e.getName().equals("exception"));
        SpanData work = otel.single("serviceTask work");
        assertThat(work.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(work.getAttributes().get(OperatonTracingAttributes.CANCEL_REASON))
                .isEqualTo("transaction rolled back");
        // only the process span remains open: the instance is still waiting for the job retry
        assertThat(plugin.getOpenSpanCount()).isEqualTo(1);
    }

    @Test
    void failedStartRollsBackItsSpans() {
        deploy(process("failstart")
                .startEvent()
                .serviceTask("work")
                .operatonClass(TracingTestSupport.Fail.class.getName())
                .endEvent()
                .done());

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("failstart"))
                .hasStackTraceContaining("boom");

        assertThat(plugin.getOpenSpanCount()).isZero();
        assertThat(otel.spans()).extracting(SpanData::getName).contains("process failstart", "serviceTask work");
    }

    @Test
    void callActivityChildIsNestedUnderTheCallActivity() {
        deploy(process("child").startEvent().userTask("wait").endEvent().done());
        deploy(process("parent")
                .startEvent()
                .callActivity("call")
                .calledElement("child")
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("parent");
        engine.getTaskService()
                .createTaskQuery()
                .list()
                .forEach(t -> engine.getTaskService().complete(t.getId()));

        SpanData call = otel.single("callActivity call");
        SpanData child = otel.single("process child");
        assertThat(child.getParentSpanId()).isEqualTo(call.getSpanId());
        assertThat(otel.single("userTask wait").getParentSpanId()).isEqualTo(child.getSpanId());
        assertThat(call.getParentSpanId())
                .isEqualTo(otel.single("process parent").getSpanId());
    }

    @Test
    void subProcessChildrenAreNestedUnderTheSubProcess() {
        deploy(process("nested")
                .startEvent()
                .subProcess("sub")
                .embeddedSubProcess()
                .startEvent("subStart")
                .serviceTask("inner")
                .operatonClass(TracingTestSupport.Noop.class.getName())
                .endEvent("subEnd")
                .subProcessDone()
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("nested");

        SpanData sub = otel.single("subProcess sub");
        assertThat(otel.single("serviceTask inner").getParentSpanId()).isEqualTo(sub.getSpanId());
        assertThat(sub.getParentSpanId())
                .isEqualTo(otel.single("process nested").getSpanId());
    }

    @Test
    void parallelWaitStatesAreSiblings() {
        deploy(process("parallel")
                .startEvent()
                .parallelGateway("fork")
                .userTask("a")
                .endEvent()
                .moveToNode("fork")
                .userTask("b")
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("parallel");
        engine.getTaskService()
                .createTaskQuery()
                .list()
                .forEach(t -> engine.getTaskService().complete(t.getId()));

        SpanData process = otel.single("process parallel");
        assertThat(otel.single("userTask a").getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(otel.single("userTask b").getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(otel.single("parallelGateway fork")).isNotNull();
    }

    @Test
    void cancellingAnInstanceEndsItsSpans() {
        deploy(process("cancel").startEvent().userTask("wait").endEvent().done());
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("cancel");

        engine.getRuntimeService().deleteProcessInstance(pi.getId(), "no longer needed");

        assertThat(otel.single("process cancel").getAttributes().get(OperatonTracingAttributes.CANCEL_REASON))
                .isEqualTo("no longer needed");
        assertThat(otel.single("userTask wait")).isNotNull();
        assertThat(plugin.getOpenSpanCount()).isZero();
    }

    @Test
    void externalTaskFetchAndCompleteAreSpansOfTheTrace() {
        deploy(process("ext")
                .startEvent()
                .serviceTask("ship")
                .operatonExternalTask("shipping")
                .endEvent()
                .done());
        engine.getRuntimeService().startProcessInstanceByKey("ext");

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(1, "worker-1")
                .topic("shipping", 60_000)
                .execute();
        engine.getExternalTaskService().complete(tasks.get(0).getId(), "worker-1");

        SpanData ship = otel.single("serviceTask ship");
        SpanData fetch = otel.single("external-task fetch shipping");
        SpanData complete = otel.single("external-task complete shipping");
        assertThat(fetch.getParentSpanId()).isEqualTo(ship.getSpanId());
        assertThat(complete.getParentSpanId()).isEqualTo(ship.getSpanId());
        assertThat(complete.getAttributes().get(OperatonTracingAttributes.EXTERNAL_TASK_WORKER_ID))
                .isEqualTo("worker-1");
        assertThat(fetch.getAttributes().get(OperatonTracingAttributes.EXTERNAL_TASK_TOPIC))
                .isEqualTo("shipping");
        assertThat(plugin.getOpenSpanCount()).isZero();
    }

    @Test
    void endOnAnotherNodeContinuesFromTheStoredTraceparent() {
        deploy(process("restart").startEvent().userTask("wait").endEvent().done());
        engine.getRuntimeService().startProcessInstanceByKey("restart");
        String traceId = otel.spans().get(0).getTraceId(); // the start event of the first node

        // a second plugin instance on the same engine does not know the open spans
        OpenTelemetryTracingPlugin other = new OpenTelemetryTracingPlugin();
        other.setOpenTelemetry(otel.sdk);
        ProcessEngine second = otherEngine(other);
        try {
            second.getTaskService()
                    .createTaskQuery()
                    .list()
                    .forEach(t -> second.getTaskService().complete(t.getId()));
        } finally {
            second.close();
        }

        SpanData task = otel.single("userTask wait");
        assertThat(task.getAttributes().get(OperatonTracingAttributes.START_MISSING))
                .isTrue();
        assertThat(task.getTraceId()).isNotEmpty();
        SpanData process = otel.single("process restart");
        assertThat(process.getAttributes().get(OperatonTracingAttributes.START_MISSING))
                .isTrue();
        assertThat(task.getParentSpanId())
                .isEqualTo(process.getParentSpanId().equals("0000000000000000") ? "" : task.getParentSpanId());
    }

    private ProcessEngine otherEngine(OpenTelemetryTracingPlugin other) {
        var cfg = (org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration)
                new org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("second-node");
        cfg.setJdbcUrl(engine.getProcessEngineConfiguration().getJdbcUrl());
        cfg.setDatabaseSchemaUpdate("false");
        cfg.setHistory("full");
        cfg.setJobExecutorActivate(false);
        cfg.getProcessEnginePlugins().add(other);
        return cfg.buildProcessEngine();
    }

    @Test
    void configurationIsValidated() {
        OpenTelemetryTracingPlugin invalid = new OpenTelemetryTracingPlugin();
        invalid.setMaxOpenSpans(0);
        assertThatThrownBy(() -> invalid.preInit(engine.getProcessEngineConfiguration()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOpenSpans");
        OpenTelemetryTracingPlugin blank = new OpenTelemetryTracingPlugin();
        blank.setTraceparentVariable(" ");
        assertThatThrownBy(() -> blank.preInit(engine.getProcessEngineConfiguration()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceparentVariable");
    }
}

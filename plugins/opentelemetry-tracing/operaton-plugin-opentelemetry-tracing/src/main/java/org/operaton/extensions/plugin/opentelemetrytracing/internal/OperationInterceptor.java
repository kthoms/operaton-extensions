/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import static org.operaton.extensions.plugin.opentelemetrytracing.OperatonTracingAttributes.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.List;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.cmd.CompleteExternalTaskCmd;
import org.operaton.bpm.engine.impl.cmd.ExecuteJobsCmd;
import org.operaton.bpm.engine.impl.cmd.ExtendLockOnExternalTaskCmd;
import org.operaton.bpm.engine.impl.cmd.ExternalTaskCmd;
import org.operaton.bpm.engine.impl.cmd.FetchExternalTasksCmd;
import org.operaton.bpm.engine.impl.cmd.HandleExternalTaskBpmnErrorCmd;
import org.operaton.bpm.engine.impl.cmd.HandleExternalTaskCmd;
import org.operaton.bpm.engine.impl.cmd.HandleExternalTaskFailureCmd;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;

/**
 * Inside the command context: spans for job executions and for the external task API commands (fetch and
 * lock, complete, failure, BPMN error, extend lock). Stateless apart from the shared {@link Tracing}.
 */
public final class OperationInterceptor extends CommandInterceptor {

    private static final CommandFields JOB_ID_FIELD = CommandFields.of(ExecuteJobsCmd.class, "jobId");
    private static final CommandFields TASK_ID = CommandFields.of(ExternalTaskCmd.class, "externalTaskId");
    private static final CommandFields TASK_WORKER = CommandFields.of(HandleExternalTaskCmd.class, "workerId");

    private final Tracing tracing;

    public OperationInterceptor(Tracing tracing) {
        this.tracing = tracing;
    }

    @Override
    public <T> T execute(Command<T> command) {
        if (command instanceof ExecuteJobsCmd && tracing.settings.traceJobs()) {
            startJobSpan(JOB_ID_FIELD.get(command));
        } else if (command instanceof HandleExternalTaskCmd && tracing.settings.traceExternalTasks()) {
            startHandleSpan((HandleExternalTaskCmd) command);
        } else if (command instanceof FetchExternalTasksCmd && tracing.settings.traceExternalTasks()) {
            Instant start = Tracing.now();
            T result = next.execute(command);
            @SuppressWarnings("unchecked")
            List<LockedExternalTask> locked = (List<LockedExternalTask>) result;
            recordFetched(locked, start);
            return result;
        }
        return next.execute(command);
    }

    private void startJobSpan(String jobId) {
        CommandContext cc = org.operaton.bpm.engine.impl.context.Context.getCommandContext();
        JobEntity job = cc.getJobManager().findJobById(jobId);
        if (job == null) {
            return; // the command itself reports the missing job
        }
        String pi = job.getProcessInstanceId();
        Context parent = pi != null ? tracing.processContext(pi) : Context.current();
        SpanBuilder b = tracing.builder("job " + job.getJobHandlerType(), parent);
        b.setAttribute(JOB_ID, job.getId());
        b.setAttribute(JOB_TYPE, job.getJobHandlerType());
        b.setAttribute(JOB_CATEGORY, job.getType());
        b.setAttribute(JOB_RETRIES, (long) job.getRetries());
        if (pi != null) {
            b.setAttribute(PROCESS_INSTANCE_ID, pi);
        }
        if (job.getProcessDefinitionId() != null) {
            b.setAttribute(PROCESS_DEFINITION_ID, job.getProcessDefinitionId());
        }
        if (job.getProcessDefinitionKey() != null) {
            b.setAttribute(PROCESS_DEFINITION_KEY, job.getProcessDefinitionKey());
        }
        if (job.getActivityId() != null) {
            b.setAttribute(ACTIVITY_ID, job.getActivityId());
        }
        if (job.getTenantId() != null) {
            b.setAttribute(TENANT_ID, job.getTenantId());
        }
        Span span = b.startSpan();
        CommandFrame frame = tracing.frames.current();
        frame.ownSpan(span);
        Context jobContext = Context.root().with(span);
        Context current = Context.current().with(span);
        if (pi != null) {
            current = current.with(Tracing.JOB_TRACE, new Tracing.JobTrace(pi, jobContext));
        }
        frame.pushScope("job:" + jobId, true, current.makeCurrent());
    }

    private Context engineApiParent(Context processTrace, SpanBuilder builder) {
        Span current = Span.current();
        if (current.getSpanContext().isValid()) {
            Span process = Span.fromContext(processTrace);
            if (process.getSpanContext().isValid()) {
                builder.addLink(process.getSpanContext());
            }
            return Context.current();
        }
        return processTrace;
    }

    private void startHandleSpan(HandleExternalTaskCmd command) {
        CommandContext cc = org.operaton.bpm.engine.impl.context.Context.getCommandContext();
        ExternalTaskEntity task = cc.getExternalTaskManager().findExternalTaskById(TASK_ID.get(command));
        if (task == null) {
            return; // the command itself reports the missing task
        }
        String operation = command instanceof CompleteExternalTaskCmd
                ? "complete"
                : command instanceof HandleExternalTaskFailureCmd
                        ? "failure"
                        : command instanceof HandleExternalTaskBpmnErrorCmd
                                ? "bpmn-error"
                                : command instanceof ExtendLockOnExternalTaskCmd ? "extend-lock" : "handle";
        Context processTrace =
                tracing.activityOrProcessContext(task.getProcessInstanceId(), task.getActivityInstanceId());
        SpanBuilder b = tracing.tracer.spanBuilder("external-task " + operation + " " + task.getTopicName());
        b.setParent(engineApiParent(processTrace, b));
        externalTaskAttributes(b, task);
        if (TASK_WORKER.get(command) != null) {
            b.setAttribute(EXTERNAL_TASK_WORKER_ID, TASK_WORKER.get(command));
        }
        tracing.frames.current().ownSpan(b.startSpan());
    }

    private void recordFetched(List<LockedExternalTask> locked, Instant start) {
        CommandContext cc = org.operaton.bpm.engine.impl.context.Context.getCommandContext();
        CommandFrame frame = tracing.frames.current();
        for (LockedExternalTask lockedTask : locked) {
            ExternalTaskEntity task = cc.getExternalTaskManager().findExternalTaskById(lockedTask.getId());
            if (task == null) {
                throw new IllegalStateException("Locked external task " + lockedTask.getId() + " is not loadable");
            }
            Context processTrace =
                    tracing.activityOrProcessContext(task.getProcessInstanceId(), task.getActivityInstanceId());
            SpanBuilder b = tracing.tracer.spanBuilder("external-task fetch " + task.getTopicName());
            b.setParent(engineApiParent(processTrace, b));
            b.setStartTimestamp(start);
            externalTaskAttributes(b, task);
            if (task.getWorkerId() != null) {
                b.setAttribute(EXTERNAL_TASK_WORKER_ID, task.getWorkerId());
            }
            Instant end = Tracing.now();
            frame.onCommit(() -> b.startSpan().end(end));
        }
    }

    private static void externalTaskAttributes(SpanBuilder b, ExternalTaskEntity task) {
        b.setAttribute(EXTERNAL_TASK_ID, task.getId());
        b.setAttribute(EXTERNAL_TASK_TOPIC, task.getTopicName());
        b.setAttribute(PROCESS_INSTANCE_ID, task.getProcessInstanceId());
        b.setAttribute(PROCESS_DEFINITION_ID, task.getProcessDefinitionId());
        b.setAttribute(PROCESS_DEFINITION_KEY, task.getProcessDefinitionKey());
        b.setAttribute(ACTIVITY_ID, task.getActivityId());
        if (task.getTenantId() != null) {
            b.setAttribute(TENANT_ID, task.getTenantId());
        }
    }
}

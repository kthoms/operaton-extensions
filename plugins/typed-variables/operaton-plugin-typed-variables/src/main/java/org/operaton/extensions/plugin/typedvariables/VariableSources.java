/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Creates {@link VariableSource}s. All sources are thin, stateless views: they hold the object they read from and
 * are as thread-safe as that object.
 *
 * <p>The source of an external task of the external task client is {@link ExternalTaskVariables}.
 */
public final class VariableSources {

    private VariableSources() {}

    /**
     * Reads from a variable scope: a {@code DelegateExecution}, {@code DelegateTask} or any other scope.
     *
     * @param scope the scope
     * @return the source
     */
    public static VariableSource scope(VariableScope scope) {
        return new ScopeSource(Objects.requireNonNull(scope, "scope"));
    }

    /**
     * Reads from a variable map. A map has no local scope: reading the local scope throws
     * {@link UnsupportedOperationException}.
     *
     * @param variables the map
     * @return the source
     */
    public static VariableSource map(VariableMap variables) {
        return new MapSource(Objects.requireNonNull(variables, "variables"), "variable map");
    }

    /**
     * Reads the variables fetched for a locked external task of the engine's own API.
     *
     * @param task the locked task
     * @return the source
     */
    public static VariableSource lockedExternalTask(LockedExternalTask task) {
        Objects.requireNonNull(task, "task");
        return new MapSource(
                Objects.requireNonNull(task.getVariables(), "task.getVariables()"),
                "locked external task " + task.getId() + " (topic " + task.getTopicName() + ")");
    }

    /**
     * Reads the variables of an execution through the runtime service, one lookup per variable.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution (or process instance) id
     * @return the source
     */
    public static VariableSource execution(RuntimeService runtimeService, String executionId) {
        return new RuntimeServiceSource(
                Objects.requireNonNull(runtimeService, "runtimeService"),
                Objects.requireNonNull(executionId, "executionId"));
    }

    /**
     * Reads the variables of a task through the task service, one lookup per variable.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the source
     */
    public static VariableSource task(TaskService taskService, String taskId) {
        return new TaskServiceSource(
                Objects.requireNonNull(taskService, "taskService"), Objects.requireNonNull(taskId, "taskId"));
    }

    static String describeScope(VariableScope scope) {
        if (scope instanceof DelegateExecution execution) {
            return "execution " + execution.getId() + " (activity " + execution.getCurrentActivityId() + ")";
        }
        if (scope instanceof DelegateTask task) {
            return "task " + task.getId() + " (" + task.getTaskDefinitionKey() + ")";
        }
        return "variable scope " + scope.getClass().getName();
    }

    private record ScopeSource(VariableScope scope) implements VariableSource {

        @Override
        public TypedValue getTyped(String name) {
            return scope.getVariableTyped(name);
        }

        @Override
        public TypedValue getLocalTyped(String name) {
            return scope.getVariableLocalTyped(name);
        }

        @Override
        public String describe() {
            return describeScope(scope);
        }
    }

    private record MapSource(VariableMap variables, String description) implements VariableSource {

        @Override
        public TypedValue getTyped(String name) {
            return variables.getValueTyped(name);
        }

        @Override
        public TypedValue getLocalTyped(String name) {
            throw new UnsupportedOperationException(
                    "A " + description + " has no local scope; read variable '" + name + "' with get() instead");
        }

        @Override
        public String describe() {
            return description;
        }
    }

    private record RuntimeServiceSource(RuntimeService runtimeService, String executionId) implements VariableSource {

        @Override
        public TypedValue getTyped(String name) {
            return runtimeService.getVariableTyped(executionId, name);
        }

        @Override
        public TypedValue getLocalTyped(String name) {
            return runtimeService.getVariableLocalTyped(executionId, name);
        }

        @Override
        public String describe() {
            return "execution " + executionId;
        }
    }

    private record TaskServiceSource(TaskService taskService, String taskId) implements VariableSource {

        @Override
        public TypedValue getTyped(String name) {
            return taskService.getVariableTyped(taskId, name);
        }

        @Override
        public TypedValue getLocalTyped(String name) {
            return taskService.getVariableLocalTyped(taskId, name);
        }

        @Override
        public String describe() {
            return "task " + taskId;
        }
    }
}

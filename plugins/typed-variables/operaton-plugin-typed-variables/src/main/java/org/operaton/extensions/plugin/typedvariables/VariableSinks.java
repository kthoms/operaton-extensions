/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Creates {@link VariableSink}s. All sinks are thin views holding the object they write to and are as thread-safe as
 * that object.
 */
public final class VariableSinks {

    private VariableSinks() {}

    /**
     * Writes to a variable scope: a {@code DelegateExecution}, {@code DelegateTask} or any other scope.
     *
     * @param scope the scope
     * @return the sink
     */
    public static VariableSink scope(VariableScope scope) {
        return new ScopeSink(Objects.requireNonNull(scope, "scope"));
    }

    /**
     * Writes to a variable map. A map has no local scope: writing to it throws {@link UnsupportedOperationException}.
     *
     * @param variables the map
     * @return the sink
     */
    public static VariableSink map(VariableMap variables) {
        return new MapSink(Objects.requireNonNull(variables, "variables"));
    }

    /**
     * Writes the variables of an execution through the runtime service.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution (or process instance) id
     * @return the sink
     */
    public static VariableSink execution(RuntimeService runtimeService, String executionId) {
        return new RuntimeServiceSink(
                Objects.requireNonNull(runtimeService, "runtimeService"),
                Objects.requireNonNull(executionId, "executionId"));
    }

    /**
     * Writes the variables of a task through the task service.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the sink
     */
    public static VariableSink task(TaskService taskService, String taskId) {
        return new TaskServiceSink(
                Objects.requireNonNull(taskService, "taskService"), Objects.requireNonNull(taskId, "taskId"));
    }

    private record ScopeSink(VariableScope scope) implements VariableSink {

        @Override
        public void set(String name, TypedValue value) {
            scope.setVariable(name, value);
        }

        @Override
        public void setLocal(String name, TypedValue value) {
            scope.setVariableLocal(name, value);
        }

        @Override
        public void remove(String name) {
            scope.removeVariable(name);
        }

        @Override
        public void removeLocal(String name) {
            scope.removeVariableLocal(name);
        }

        @Override
        public String describe() {
            return VariableSources.describeScope(scope);
        }
    }

    private record MapSink(VariableMap variables) implements VariableSink {

        @Override
        public void set(String name, TypedValue value) {
            variables.putValueTyped(name, value);
        }

        @Override
        public void setLocal(String name, TypedValue value) {
            throw noLocalScope(name);
        }

        @Override
        public void remove(String name) {
            variables.remove(name);
        }

        @Override
        public void removeLocal(String name) {
            throw noLocalScope(name);
        }

        @Override
        public String describe() {
            return "variable map";
        }

        private static UnsupportedOperationException noLocalScope(String name) {
            return new UnsupportedOperationException(
                    "A variable map has no local scope; use the non-local operation for variable '" + name + "'");
        }
    }

    private record RuntimeServiceSink(RuntimeService runtimeService, String executionId) implements VariableSink {

        @Override
        public void set(String name, TypedValue value) {
            runtimeService.setVariable(executionId, name, value);
        }

        @Override
        public void setLocal(String name, TypedValue value) {
            runtimeService.setVariableLocal(executionId, name, value);
        }

        @Override
        public void remove(String name) {
            runtimeService.removeVariable(executionId, name);
        }

        @Override
        public void removeLocal(String name) {
            runtimeService.removeVariableLocal(executionId, name);
        }

        @Override
        public String describe() {
            return "execution " + executionId;
        }
    }

    private record TaskServiceSink(TaskService taskService, String taskId) implements VariableSink {

        @Override
        public void set(String name, TypedValue value) {
            taskService.setVariable(taskId, name, value);
        }

        @Override
        public void setLocal(String name, TypedValue value) {
            taskService.setVariableLocal(taskId, name, value);
        }

        @Override
        public void remove(String name) {
            taskService.removeVariable(taskId, name);
        }

        @Override
        public void removeLocal(String name) {
            taskService.removeVariableLocal(taskId, name);
        }

        @Override
        public String describe() {
            return "task " + taskId;
        }
    }
}

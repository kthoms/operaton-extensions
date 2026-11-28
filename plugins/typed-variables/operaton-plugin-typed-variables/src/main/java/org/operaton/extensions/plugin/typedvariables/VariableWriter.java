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

/**
 * Writes declared variables to one sink, with a fluent interface. Use {@link Plain} directly or extend this class to
 * make a typed variable set with one method per variable.
 *
 * <pre>{@code
 * public final class OrderWriter extends VariableWriter<OrderWriter> {
 *     public OrderWriter(VariableScope scope) { super(VariableSinks.scope(scope)); }
 *     public OrderWriter orderId(String id) { return set(OrderVariables.ORDER_ID, id); }
 * }
 * }</pre>
 *
 * <p>The writer holds only its sink; thread-safety is that of the sink.
 *
 * @param <S> the concrete writer type, returned by the fluent methods
 */
public class VariableWriter<S extends VariableWriter<S>> {

    private final VariableSink sink;

    /**
     * Creates a writer over a sink. Subclasses call this from their constructors.
     *
     * @param sink where to write to
     */
    protected VariableWriter(VariableSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** A writer without accessors of its own, for ad-hoc use. */
    public static final class Plain extends VariableWriter<Plain> {

        private Plain(VariableSink sink) {
            super(sink);
        }
    }

    /**
     * Creates a plain writer over any sink.
     *
     * @param sink where to write to
     * @return the writer
     */
    public static Plain of(VariableSink sink) {
        return new Plain(sink);
    }

    /**
     * Creates a plain writer over a variable scope such as a {@code DelegateExecution}.
     *
     * @param scope the scope
     * @return the writer
     */
    public static Plain of(VariableScope scope) {
        return new Plain(VariableSinks.scope(scope));
    }

    /**
     * Creates a plain writer over a variable map.
     *
     * @param variables the map
     * @return the writer
     */
    public static Plain of(VariableMap variables) {
        return new Plain(VariableSinks.map(variables));
    }

    /**
     * Creates a plain writer over an execution, writing through the runtime service.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution or process instance id
     * @return the writer
     */
    public static Plain of(RuntimeService runtimeService, String executionId) {
        return new Plain(VariableSinks.execution(runtimeService, executionId));
    }

    /**
     * Creates a plain writer over a task, writing through the task service.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the writer
     */
    public static Plain ofTask(TaskService taskService, String taskId) {
        return new Plain(VariableSinks.task(taskService, taskId));
    }

    /**
     * Sets a variable.
     *
     * @param variable the declaration
     * @param value the value, not {@code null}
     * @param <T> the Java type
     * @return this writer
     */
    public <T> S set(VariableFactory<T> variable, T value) {
        variable.on(sink).set(value);
        return self();
    }

    /**
     * Sets a variable in the local scope.
     *
     * @param variable the declaration
     * @param value the value, not {@code null}
     * @param <T> the Java type
     * @return this writer
     */
    public <T> S setLocal(VariableFactory<T> variable, T value) {
        variable.on(sink).setLocal(value);
        return self();
    }

    /**
     * Removes a variable.
     *
     * @param variable the declaration
     * @return this writer
     */
    public S remove(VariableFactory<?> variable) {
        variable.on(sink).remove();
        return self();
    }

    /**
     * Removes a variable from the local scope.
     *
     * @param variable the declaration
     * @return this writer
     */
    public S removeLocal(VariableFactory<?> variable) {
        variable.on(sink).removeLocal();
        return self();
    }

    @SuppressWarnings("unchecked")
    private S self() {
        return (S) this;
    }
}

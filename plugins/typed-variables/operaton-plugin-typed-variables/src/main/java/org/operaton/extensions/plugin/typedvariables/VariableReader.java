/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import java.util.Optional;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.variable.VariableMap;

/**
 * Reads declared variables from one source. Use it directly ({@code VariableReader.of(execution).get(ORDER_ID)}) or
 * extend it to make a typed variable set: a class per process with one accessor per variable, reused by delegates,
 * workers and tests.
 *
 * <pre>{@code
 * public final class OrderReader extends VariableReader {
 *     public OrderReader(VariableScope scope) { super(VariableSources.scope(scope)); }
 *     public String orderId() { return get(OrderVariables.ORDER_ID); }
 * }
 * }</pre>
 *
 * <p>The reader holds only its source; thread-safety is that of the source.
 */
public class VariableReader {

    private final VariableSource source;

    /**
     * Creates a reader over a source. Subclasses call this from their constructors.
     *
     * @param source where to read from
     */
    protected VariableReader(VariableSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Creates a reader over any source.
     *
     * @param source where to read from
     * @return the reader
     */
    public static VariableReader of(VariableSource source) {
        return new VariableReader(source);
    }

    /**
     * Creates a reader over a variable scope such as a {@code DelegateExecution}.
     *
     * @param scope the scope
     * @return the reader
     */
    public static VariableReader of(VariableScope scope) {
        return new VariableReader(VariableSources.scope(scope));
    }

    /**
     * Creates a reader over a variable map.
     *
     * @param variables the map
     * @return the reader
     */
    public static VariableReader of(VariableMap variables) {
        return new VariableReader(VariableSources.map(variables));
    }

    /**
     * Creates a reader over an execution, reading through the runtime service.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution or process instance id
     * @return the reader
     */
    public static VariableReader of(RuntimeService runtimeService, String executionId) {
        return new VariableReader(VariableSources.execution(runtimeService, executionId));
    }

    /**
     * Creates a reader over a task, reading through the task service.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the reader
     */
    public static VariableReader ofTask(TaskService taskService, String taskId) {
        return new VariableReader(VariableSources.task(taskService, taskId));
    }

    /**
     * Reads a variable; it must exist.
     *
     * @param variable the declaration
     * @param <T> the Java type
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if missing or {@code null}
     */
    public <T> T get(VariableFactory<T> variable) {
        return variable.from(source).get();
    }

    /**
     * Reads a variable.
     *
     * @param variable the declaration
     * @param <T> the Java type
     * @return the value, or empty if missing or {@code null}
     */
    public <T> Optional<T> getOptional(VariableFactory<T> variable) {
        return variable.from(source).getOptional();
    }

    /**
     * Reads a variable, falling back to a default when it is absent.
     *
     * @param variable the declaration
     * @param defaultValue the value to return when the variable is missing or {@code null}
     * @param <T> the Java type
     * @return the value or the default
     */
    public <T> T getOrDefault(VariableFactory<T> variable, T defaultValue) {
        return variable.from(source).getOrDefault(defaultValue);
    }

    /**
     * Reads a variable of the local scope; it must exist there.
     *
     * @param variable the declaration
     * @param <T> the Java type
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the local scope has no such variable
     */
    public <T> T getLocal(VariableFactory<T> variable) {
        return variable.from(source).getLocal();
    }

    /**
     * Reads a variable of the local scope.
     *
     * @param variable the declaration
     * @param <T> the Java type
     * @return the value, or empty if absent locally
     */
    public <T> Optional<T> getLocalOptional(VariableFactory<T> variable) {
        return variable.from(source).getLocalOptional();
    }

    /**
     * Tells whether a variable exists with a value.
     *
     * @param variable the declaration
     * @return {@code true} if present and not {@code null}
     */
    public boolean isPresent(VariableFactory<?> variable) {
        return variable.from(source).isPresent();
    }
}

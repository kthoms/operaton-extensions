/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Optional;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Declares one process variable: its name and its type. Declare it once, typically as a constant of a class per
 * process ({@code public static final VariableFactory<String> ORDER_ID = stringVariable("orderId");}), and use it
 * wherever the variable is read or written: delegates, listeners, external task workers, tests.
 *
 * <p>Create factories with the static methods of {@link TypedVariables}. Factories are immutable and thread-safe.
 *
 * <p>The methods come in two layers. The shorthand methods ({@link #get(VariableScope)},
 * {@link #set(VariableScope, Object)}, ...) cover the common cases. {@code from(...)} returns a {@link ReadAdapter} and
 * {@code on(...)} a {@link WriteAdapter} for every kind of source and sink, including the local-scope variants and
 * the engine's services.
 *
 * <p>Semantics, for every source: a variable is <em>absent</em> when it does not exist or is {@code null};
 * {@code get} fails loudly with {@link VariableNotFoundException} for an absent variable and with
 * {@link WrongVariableTypeException} for a value of another type; {@code set} refuses {@code null}.
 *
 * <p>For the variables of an external task of the external task client use {@link ExternalTaskVariables}; keeping
 * that API out of this interface means code compiled against the factory does not need the client on its classpath.
 *
 * @param <T> the Java type of the variable
 */
public interface VariableFactory<T> {

    /**
     * Returns the variable name as it appears in the engine and in BPMN expressions.
     *
     * @return the name
     */
    String getName();

    /**
     * Returns the codec that converts between {@code T} and the engine's typed value.
     *
     * @return the codec
     */
    VariableCodec<T> getCodec();

    /**
     * Converts a value to the typed value that is stored.
     *
     * @param value the value, not {@code null}
     * @return the typed value
     * @throws IllegalArgumentException if the value is {@code null}
     * @throws TypedVariableException if the value cannot be stored in the declared type
     */
    default TypedValue toTypedValue(T value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Cannot set variable '" + getName()
                            + "' to null; use remove() to clear a variable (null and missing are the same for typed variables)");
        }
        return getCodec().encode(getName(), value);
    }

    // ---------------------------------------------------------------- adapters

    /**
     * Reads this variable from a variable source.
     *
     * @param source the source
     * @return the read adapter
     */
    default ReadAdapter<T> from(VariableSource source) {
        return new ReadAdapter<>(this, source);
    }

    /**
     * Reads this variable from a variable scope such as a {@code DelegateExecution} or {@code DelegateTask}.
     *
     * @param scope the scope
     * @return the read adapter
     */
    default ReadAdapter<T> from(VariableScope scope) {
        return from(VariableSources.scope(scope));
    }

    /**
     * Reads this variable from a variable map.
     *
     * @param variables the map
     * @return the read adapter
     */
    default ReadAdapter<T> from(VariableMap variables) {
        return from(VariableSources.map(variables));
    }

    /**
     * Reads this variable from a locked external task of the engine API.
     *
     * @param task the task
     * @return the read adapter
     */
    default ReadAdapter<T> from(LockedExternalTask task) {
        return from(VariableSources.lockedExternalTask(task));
    }

    /**
     * Reads this variable from an execution through the runtime service.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution or process instance id
     * @return the read adapter
     */
    default ReadAdapter<T> from(RuntimeService runtimeService, String executionId) {
        return from(VariableSources.execution(runtimeService, executionId));
    }

    /**
     * Reads this variable from a task through the task service.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the read adapter
     */
    default ReadAdapter<T> fromTask(TaskService taskService, String taskId) {
        return from(VariableSources.task(taskService, taskId));
    }

    /**
     * Writes this variable to a variable sink.
     *
     * @param sink the sink
     * @return the write adapter
     */
    default WriteAdapter<T> on(VariableSink sink) {
        return new WriteAdapter<>(this, sink);
    }

    /**
     * Writes this variable to a variable scope such as a {@code DelegateExecution} or {@code DelegateTask}.
     *
     * @param scope the scope
     * @return the write adapter
     */
    default WriteAdapter<T> on(VariableScope scope) {
        return on(VariableSinks.scope(scope));
    }

    /**
     * Writes this variable to a variable map.
     *
     * @param variables the map
     * @return the write adapter
     */
    default WriteAdapter<T> on(VariableMap variables) {
        return on(VariableSinks.map(variables));
    }

    /**
     * Writes this variable to an execution through the runtime service.
     *
     * @param runtimeService the runtime service
     * @param executionId the execution or process instance id
     * @return the write adapter
     */
    default WriteAdapter<T> on(RuntimeService runtimeService, String executionId) {
        return on(VariableSinks.execution(runtimeService, executionId));
    }

    /**
     * Writes this variable to a task through the task service.
     *
     * @param taskService the task service
     * @param taskId the task id
     * @return the write adapter
     */
    default WriteAdapter<T> onTask(TaskService taskService, String taskId) {
        return on(VariableSinks.task(taskService, taskId));
    }

    // ---------------------------------------------------------------- shorthands for a variable scope

    /**
     * Reads the variable; it must exist.
     *
     * @param scope the scope to read from
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the variable is missing or {@code null}
     * @throws WrongVariableTypeException if the value has another type
     */
    default T get(VariableScope scope) {
        return from(scope).get();
    }

    /**
     * Reads the variable.
     *
     * @param scope the scope to read from
     * @return the value, or empty if missing or {@code null}
     */
    default Optional<T> getOptional(VariableScope scope) {
        return from(scope).getOptional();
    }

    /**
     * Reads the variable from the local scope; it must exist there.
     *
     * @param scope the scope to read from
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the local scope has no such variable
     */
    default T getLocal(VariableScope scope) {
        return from(scope).getLocal();
    }

    /**
     * Tells whether the variable exists with a value.
     *
     * @param scope the scope to look in
     * @return {@code true} if present and not {@code null}
     */
    default boolean isPresent(VariableScope scope) {
        return from(scope).isPresent();
    }

    /**
     * Sets the variable.
     *
     * @param scope the scope to write to
     * @param value the value, not {@code null}
     */
    default void set(VariableScope scope, T value) {
        on(scope).set(value);
    }

    /**
     * Sets the variable in the local scope.
     *
     * @param scope the scope to write to
     * @param value the value, not {@code null}
     */
    default void setLocal(VariableScope scope, T value) {
        on(scope).setLocal(value);
    }

    /**
     * Removes the variable.
     *
     * @param scope the scope to remove it from
     */
    default void remove(VariableScope scope) {
        on(scope).remove();
    }

    /**
     * Removes the variable from the local scope.
     *
     * @param scope the scope to remove it from
     */
    default void removeLocal(VariableScope scope) {
        on(scope).removeLocal();
    }

    // ---------------------------------------------------------------- shorthands for a variable map

    /**
     * Reads the variable from a variable map; it must exist.
     *
     * @param variables the map
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the variable is missing or {@code null}
     */
    default T get(VariableMap variables) {
        return from(variables).get();
    }

    /**
     * Reads the variable from a variable map.
     *
     * @param variables the map
     * @return the value, or empty if missing or {@code null}
     */
    default Optional<T> getOptional(VariableMap variables) {
        return from(variables).getOptional();
    }

    /**
     * Tells whether the map holds the variable with a value.
     *
     * @param variables the map
     * @return {@code true} if present and not {@code null}
     */
    default boolean isPresent(VariableMap variables) {
        return from(variables).isPresent();
    }

    /**
     * Sets the variable in a variable map.
     *
     * @param variables the map to modify
     * @param value the value, not {@code null}
     * @return the same map, for chaining
     */
    default VariableMap set(VariableMap variables, T value) {
        on(variables).set(value);
        return variables;
    }

    /**
     * Removes the variable from a variable map.
     *
     * @param variables the map to modify
     * @return the same map, for chaining
     */
    default VariableMap remove(VariableMap variables) {
        on(variables).remove();
        return variables;
    }
}

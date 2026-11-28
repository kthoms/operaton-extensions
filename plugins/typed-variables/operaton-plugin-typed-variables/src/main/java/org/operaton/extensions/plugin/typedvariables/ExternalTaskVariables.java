/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * The variables of an {@link ExternalTask} of the external task client, read through declared variables. It is a
 * {@link VariableReader} for the worker's handler and a {@link VariableSource}, so it also works with
 * {@code factory.from(...)}, {@link VariableSet#requirePresent(VariableSource)} and the test assertions.
 *
 * <pre>{@code
 * client.subscribe("price-order").variables(INPUT.getNamesArray()).handler((task, service) -> {
 *     ExternalTaskVariables variables = ExternalTaskVariables.of(task);
 *     Order order = variables.get(OrderVariables.ORDER);
 *     service.complete(task, TypedVariables.builder().set(OrderVariables.TOTAL_CENTS, price(order)).build());
 * }).open();
 * }</pre>
 *
 * <p>Only the variables the worker subscribed to are present on the task; reading another one fails with
 * {@link VariableNotFoundException}. An external task has no local scope. This is the only class of the extension that
 * needs {@code operaton-external-task-client} on the classpath, at compile time and at run time; it is kept apart so
 * engine-side code does not need that jar.
 *
 * <p>Immutable; thread-safety is that of the task object.
 */
public final class ExternalTaskVariables extends VariableReader implements VariableSource {

    private final TaskSource source;

    private ExternalTaskVariables(TaskSource source) {
        super(source);
        this.source = source;
    }

    /**
     * Creates the view over a task.
     *
     * @param task the task handed to the worker's handler
     * @return the variables of the task
     */
    public static ExternalTaskVariables of(ExternalTask task) {
        return new ExternalTaskVariables(new TaskSource(Objects.requireNonNull(task, "task")));
    }

    @Override
    public TypedValue getTyped(String name) {
        return source.getTyped(name);
    }

    @Override
    public TypedValue getLocalTyped(String name) {
        return source.getLocalTyped(name);
    }

    @Override
    public String describe() {
        return source.describe();
    }

    private record TaskSource(ExternalTask task) implements VariableSource {

        @Override
        public TypedValue getTyped(String name) {
            return task.getVariableTyped(name);
        }

        @Override
        public TypedValue getLocalTyped(String name) {
            throw new UnsupportedOperationException(
                    "An external task has no local scope; read variable '" + name + "' with get() instead");
        }

        @Override
        public String describe() {
            return "external task " + task.getId() + " (topic " + task.getTopicName() + ")";
        }
    }
}

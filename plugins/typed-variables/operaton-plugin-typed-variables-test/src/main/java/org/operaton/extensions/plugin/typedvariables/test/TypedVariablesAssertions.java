/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.test;

import java.util.Collection;
import java.util.Objects;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineServices;
import org.operaton.bpm.engine.ProcessEngines;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.extensions.plugin.typedvariables.VariableSource;
import org.operaton.extensions.plugin.typedvariables.VariableSources;

/**
 * Entry points of the typed variable assertions. Static-import {@code assertThat} next to AssertJ's own:
 *
 * <pre>{@code
 * import static org.assertj.core.api.Assertions.assertThat;
 * import static org.operaton.extensions.plugin.typedvariables.test.TypedVariablesAssertions.assertThat;
 *
 * assertThat(processInstance).hasVariable(ORDER_ID, "A-1").hasNoVariable(ERRORS);
 * assertThat(engine, processInstance).variable(TOTAL_CENTS).isGreaterThan(0L);
 * }</pre>
 *
 * <p>The overloads without an engine find it themselves: they use the one engine that is registered in
 * {@link ProcessEngines}, which is where {@code ProcessEngineExtension} puts it. With several engines registered or
 * none they fail loudly; pass the engine ({@code ProcessEngineExtension} and {@code ProcessEngine} both are a
 * {@link ProcessEngineServices}) as first argument then.
 */
public final class TypedVariablesAssertions {

    private TypedVariablesAssertions() {}

    /**
     * Asserts on the variables of a running process instance (or any execution), found through the single registered
     * engine.
     *
     * @param execution the process instance or execution
     * @return the assertion
     * @throws IllegalStateException if no engine or more than one engine is registered
     */
    public static VariablesAssert assertThat(Execution execution) {
        return assertThat(registeredEngine(), execution);
    }

    /**
     * Asserts on the variables of a running process instance (or any execution).
     *
     * @param services the engine, for example the {@code ProcessEngineExtension} of the test
     * @param execution the process instance or execution
     * @return the assertion
     */
    public static VariablesAssert assertThat(ProcessEngineServices services, Execution execution) {
        Objects.requireNonNull(execution, "execution");
        return new VariablesAssert(VariableSources.execution(
                Objects.requireNonNull(services, "services").getRuntimeService(), execution.getId()));
    }

    /**
     * Asserts on the variables of a historic process instance, found through the single registered engine. Needs
     * history level {@code audit} or {@code full}.
     *
     * @param instance the historic process instance
     * @return the assertion
     * @throws IllegalStateException if no engine or more than one engine is registered
     */
    public static VariablesAssert assertThat(HistoricProcessInstance instance) {
        return assertThat(registeredEngine(), instance);
    }

    /**
     * Asserts on the variables of a historic process instance. Needs history level {@code audit} or {@code full}.
     *
     * @param services the engine, for example the {@code ProcessEngineExtension} of the test
     * @param instance the historic process instance
     * @return the assertion
     */
    public static VariablesAssert assertThat(ProcessEngineServices services, HistoricProcessInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return new VariablesAssert(new HistoricVariableSource(
                Objects.requireNonNull(services, "services").getHistoryService(), instance.getId()));
    }

    /**
     * Asserts on a variable scope, for example a {@code DelegateExecution} captured in a unit test of a delegate.
     *
     * @param scope the scope
     * @return the assertion
     */
    public static VariablesAssert assertThat(VariableScope scope) {
        return new VariablesAssert(VariableSources.scope(Objects.requireNonNull(scope, "scope")));
    }

    /**
     * Asserts on a variable map, for example the variables a worker completes a task with.
     *
     * @param variables the map
     * @return the assertion
     */
    public static VariablesAssert assertThat(VariableMap variables) {
        return new VariablesAssert(VariableSources.map(Objects.requireNonNull(variables, "variables")));
    }

    /**
     * Asserts on any variable source.
     *
     * @param source the source
     * @return the assertion
     */
    public static VariablesAssert assertThat(VariableSource source) {
        return new VariablesAssert(Objects.requireNonNull(source, "source"));
    }

    private static ProcessEngine registeredEngine() {
        Collection<ProcessEngine> engines = ProcessEngines.getProcessEngines().values();
        if (engines.size() != 1) {
            throw new IllegalStateException("Expected exactly one registered process engine but found "
                    + engines.size()
                    + (engines.isEmpty()
                            ? "; start one (ProcessEngineExtension does) or pass the engine as first argument"
                            : "; pass the engine to use as first argument: assertThat(engine, processInstance)"));
        }
        return engines.iterator().next();
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactory;
import org.assertj.core.api.ObjectAssert;
import org.operaton.extensions.plugin.typedvariables.VariableFactory;
import org.operaton.extensions.plugin.typedvariables.VariableSet;
import org.operaton.extensions.plugin.typedvariables.VariableSource;
import org.operaton.extensions.plugin.typedvariables.WrongVariableTypeException;

/**
 * Assertions on the declared variables of a process instance, execution, historic process instance, variable scope or
 * variable map. Create it with the {@code assertThat} methods of {@link TypedVariablesAssertions}.
 *
 * <p>A variable counts as present when it exists with a non-null value. A value of the wrong type fails the assertion
 * with the {@link WrongVariableTypeException} as its cause.
 */
public class VariablesAssert extends AbstractAssert<VariablesAssert, VariableSource> {

    /**
     * Creates the assertion over a source.
     *
     * @param source the variables to assert on
     */
    public VariablesAssert(VariableSource source) {
        super(source, VariablesAssert.class);
    }

    /**
     * Verifies that the variable is present.
     *
     * @param variable the declaration
     * @return this assertion
     */
    public VariablesAssert hasVariable(VariableFactory<?> variable) {
        isNotNull();
        read(variable, false);
        return this;
    }

    /**
     * Verifies that the variable is present and equal to the expected value.
     *
     * @param variable the declaration
     * @param expected the expected value
     * @param <T> the Java type
     * @return this assertion
     */
    public <T> VariablesAssert hasVariable(VariableFactory<T> variable, T expected) {
        isNotNull();
        T value = read(variable, false);
        if (!Objects.equals(value, expected)) {
            throw assertionError(
                    "Expecting %s to have variable '%s' with value%n  <%s>%nbut it was%n  <%s>",
                    describe(), variable.getName(), expected, value);
        }
        return this;
    }

    /**
     * Verifies that the variable is present and passes further assertions.
     *
     * @param variable the declaration
     * @param requirements assertions on the value
     * @param <T> the Java type
     * @return this assertion
     */
    public <T> VariablesAssert hasVariableSatisfying(VariableFactory<T> variable, Consumer<? super T> requirements) {
        isNotNull();
        requirements.accept(read(variable, false));
        return this;
    }

    /**
     * Verifies that the variable is absent: it does not exist or is {@code null}.
     *
     * @param variable the declaration
     * @return this assertion
     */
    public VariablesAssert hasNoVariable(VariableFactory<?> variable) {
        isNotNull();
        Optional<?> value = readOptional(variable, false);
        if (value.isPresent()) {
            throw assertionError(
                    "Expecting %s to have no variable '%s' but it was%n  <%s>",
                    describe(), variable.getName(), value.get());
        }
        return this;
    }

    /**
     * Verifies that the variable is present in the local scope.
     *
     * @param variable the declaration
     * @return this assertion
     */
    public VariablesAssert hasLocalVariable(VariableFactory<?> variable) {
        isNotNull();
        read(variable, true);
        return this;
    }

    /**
     * Verifies that the variable is present in the local scope and equal to the expected value.
     *
     * @param variable the declaration
     * @param expected the expected value
     * @param <T> the Java type
     * @return this assertion
     */
    public <T> VariablesAssert hasLocalVariable(VariableFactory<T> variable, T expected) {
        isNotNull();
        T value = read(variable, true);
        if (!Objects.equals(value, expected)) {
            throw assertionError(
                    "Expecting %s to have local variable '%s' with value%n  <%s>%nbut it was%n  <%s>",
                    describe(), variable.getName(), expected, value);
        }
        return this;
    }

    /**
     * Verifies that the variable is absent from the local scope.
     *
     * @param variable the declaration
     * @return this assertion
     */
    public VariablesAssert hasNoLocalVariable(VariableFactory<?> variable) {
        isNotNull();
        Optional<?> value = readOptional(variable, true);
        if (value.isPresent()) {
            throw assertionError(
                    "Expecting %s to have no local variable '%s' but it was%n  <%s>",
                    describe(), variable.getName(), value.get());
        }
        return this;
    }

    /**
     * Verifies that every variable of the set is present.
     *
     * @param variables the set
     * @return this assertion
     */
    public VariablesAssert hasAllVariables(VariableSet variables) {
        isNotNull();
        List<String> missing = new ArrayList<>();
        for (VariableFactory<?> variable : variables.getVariables()) {
            if (readOptional(variable, false).isEmpty()) {
                missing.add(variable.getName());
            }
        }
        if (!missing.isEmpty()) {
            throw assertionError(
                    "Expecting %s to have all variables but these are missing or null: %s", describe(), missing);
        }
        return this;
    }

    /**
     * Continues with assertions on the value of a variable, which must be present.
     *
     * <pre>{@code
     * assertThat(processInstance).variable(TOTAL_CENTS).isEqualTo(1999L);
     * }</pre>
     *
     * @param variable the declaration
     * @param <T> the Java type
     * @return AssertJ's assertion over the value
     */
    public <T> ObjectAssert<T> variable(VariableFactory<T> variable) {
        isNotNull();
        return Assertions.assertThat(read(variable, false)).as("variable '%s' of %s", variable.getName(), describe());
    }

    /**
     * Continues with assertions of a specific AssertJ type on the value of a variable, which must be present.
     *
     * <pre>{@code
     * assertThat(processInstance).variable(TOTAL_CENTS, InstanceOfAssertFactories.LONG).isGreaterThan(0L);
     * assertThat(processInstance).variable(ERRORS, InstanceOfAssertFactories.list(String.class)).isEmpty();
     * }</pre>
     *
     * @param variable the declaration
     * @param assertFactory the AssertJ factory of the assertion type, for example
     *     {@link org.assertj.core.api.InstanceOfAssertFactories#LONG}
     * @param <T> the Java type
     * @param <A> the assertion type
     * @return the assertion over the value
     */
    public <T, A extends AbstractAssert<?, ?>> A variable(
            VariableFactory<T> variable, InstanceOfAssertFactory<?, A> assertFactory) {
        return variable(variable).asInstanceOf(assertFactory);
    }

    private String describe() {
        return actual.describe();
    }

    private <T> T read(VariableFactory<T> variable, boolean local) {
        return readOptional(variable, local)
                .orElseThrow(() -> assertionError(
                        "Expecting %s to have %svariable '%s' but it is missing or null",
                        describe(), local ? "local " : "", variable.getName()));
    }

    private <T> Optional<T> readOptional(VariableFactory<T> variable, boolean local) {
        try {
            return local
                    ? variable.from(actual).getLocalOptional()
                    : variable.from(actual).getOptional();
        } catch (WrongVariableTypeException e) {
            AssertionError error = assertionError("%s", e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    private AssertionError assertionError(String message, Object... arguments) {
        String text = String.format(message, arguments);
        return new AssertionError(info.hasDescription() ? "[" + info.descriptionText() + "] " + text : text);
    }
}

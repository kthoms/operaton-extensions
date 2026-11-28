/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.variable.VariableMap;

/**
 * A group of declared variables, usually all the variables of one process. Build it from the constants class of the
 * process ({@code VariableSet.of(OrderVariables.class)}) to get the names for fetching and a check that a scope,
 * map or source holds them all.
 *
 * <pre>{@code
 * private static final VariableSet INPUT = VariableSet.of(OrderVariables.ORDER_ID, OrderVariables.ORDER);
 * client.subscribe("price-order").handler(handler).variables(INPUT.getNamesArray()).open();
 * INPUT.requirePresent(externalTask);   // fails with one message listing every missing variable
 * }</pre>
 *
 * <p>Immutable and thread-safe.
 */
public final class VariableSet {

    private final Map<String, VariableFactory<?>> byName;

    private VariableSet(Map<String, VariableFactory<?>> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /**
     * Collects the variable declarations held by the {@code static} fields of a class; fields of other types are
     * ignored.
     *
     * @param constants the class with the declarations, for example {@code OrderVariables.class}
     * @return the set
     * @throws IllegalArgumentException if two declarations share a name or the class declares none
     */
    public static VariableSet of(Class<?> constants) {
        Objects.requireNonNull(constants, "constants");
        List<VariableFactory<?>> found = new ArrayList<>();
        for (Field field : constants.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && VariableFactory.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    found.add((VariableFactory<?>) field.get(null));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException(
                            "Cannot read the variable declaration " + constants.getName() + "." + field.getName(), e);
                }
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    constants.getName() + " has no static VariableFactory field; declare the variables as constants");
        }
        return of(found);
    }

    /**
     * Creates a set from declarations.
     *
     * @param variables the declarations
     * @return the set
     * @throws IllegalArgumentException if two declarations share a name
     */
    public static VariableSet of(VariableFactory<?>... variables) {
        return of(List.of(variables));
    }

    /**
     * Creates a set from declarations.
     *
     * @param variables the declarations
     * @return the set
     * @throws IllegalArgumentException if two declarations share a name
     */
    public static VariableSet of(List<VariableFactory<?>> variables) {
        Map<String, VariableFactory<?>> byName = new LinkedHashMap<>();
        for (VariableFactory<?> variable : variables) {
            VariableFactory<?> previous = byName.put(variable.getName(), variable);
            if (previous != null) {
                throw new IllegalArgumentException("Variable name '" + variable.getName() + "' is declared twice");
            }
        }
        return new VariableSet(byName);
    }

    /**
     * Returns the declarations in declaration order.
     *
     * @return an unmodifiable list
     */
    public List<VariableFactory<?>> getVariables() {
        return List.copyOf(byName.values());
    }

    /**
     * Returns the variable names in declaration order.
     *
     * @return an unmodifiable set
     */
    public Set<String> getNames() {
        return byName.keySet();
    }

    /**
     * Returns the variable names, for APIs that take an array such as the external task client's subscription.
     *
     * @return a new array
     */
    public String[] getNamesArray() {
        return byName.keySet().toArray(String[]::new);
    }

    /**
     * Checks that every variable of the set exists with a value of its declared type.
     *
     * @param source where to look
     * @throws VariableNotFoundException listing all missing variables
     * @throws WrongVariableTypeException for the first variable of the wrong type
     */
    public void requirePresent(VariableSource source) {
        List<String> missing = new ArrayList<>();
        for (VariableFactory<?> variable : byName.values()) {
            if (variable.from(source).getOptional().isEmpty()) {
                missing.add(variable.getName());
            }
        }
        if (!missing.isEmpty()) {
            throw new VariableNotFoundException(missing, source.describe());
        }
    }

    /**
     * Checks that every variable of the set exists in a scope.
     *
     * @param scope the scope
     * @throws VariableNotFoundException listing all missing variables
     */
    public void requirePresent(VariableScope scope) {
        requirePresent(VariableSources.scope(scope));
    }

    /**
     * Checks that every variable of the set exists in a map.
     *
     * @param variables the map
     * @throws VariableNotFoundException listing all missing variables
     */
    public void requirePresent(VariableMap variables) {
        requirePresent(VariableSources.map(variables));
    }
}

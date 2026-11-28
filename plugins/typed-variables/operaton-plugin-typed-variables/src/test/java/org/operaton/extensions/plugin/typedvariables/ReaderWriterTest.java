/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER_ID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PAID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.QUANTITY;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TOTAL_CENTS;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.plugin.typedvariables.TestVariables.Status;

/** The reader/writer pair and the variable set: a typed group of variables defined once. */
class ReaderWriterTest {

    /** A typed variable set for one process: the reader side. */
    static final class OrderReader extends VariableReader {
        OrderReader(VariableMap variables) {
            super(VariableSources.map(variables));
        }

        String orderId() {
            return get(ORDER_ID);
        }

        boolean paid() {
            return getOrDefault(PAID, false);
        }
    }

    /** The writer side, fluent. */
    static final class OrderWriter extends VariableWriter<OrderWriter> {
        OrderWriter(VariableMap variables) {
            super(VariableSinks.map(variables));
        }

        OrderWriter orderId(String id) {
            return set(ORDER_ID, id);
        }

        OrderWriter status(Status status) {
            return set(STATUS, status);
        }
    }

    @Test
    void aTypedSetIsDefinedOnceAndUsedForReadingAndWriting() {
        VariableMap map = Variables.createVariables();

        new OrderWriter(map).orderId("A-9").status(Status.PRICED);
        OrderReader reader = new OrderReader(map);

        assertThat(reader.orderId()).isEqualTo("A-9");
        assertThat(reader.paid()).isFalse();
        assertThat(reader.get(STATUS)).isEqualTo(Status.PRICED);
        assertThat(reader.getOptional(QUANTITY)).isEmpty();
        assertThat(reader.isPresent(STATUS)).isTrue();
        assertThatThrownBy(() -> reader.get(QUANTITY))
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessage("Required variable 'quantity' is missing or null in variable map");
    }

    @Test
    void plainWriterChainsAndRemoves() {
        VariableMap map = Variables.createVariables();

        VariableWriter.of(map)
                .set(ORDER_ID, "A-1")
                .set(TOTAL_CENTS, 5L)
                .set(PAID, true)
                .remove(PAID);

        assertThat(map.keySet()).containsExactlyInAnyOrder("orderId", "totalCents");
        assertThat(VariableReader.of(map).get(TOTAL_CENTS)).isEqualTo(5L);
    }

    @Test
    void aSetCollectsTheDeclarationsOfAConstantsClass() {
        VariableSet all = VariableSet.of(TestVariables.class);

        assertThat(all.getNames()).hasSize(13).contains("orderId", "order", "tags", "picked");
        assertThat(all.getVariables()).contains(ORDER_ID);
    }

    @Test
    void aSetRefusesDuplicatesAndClassesWithoutDeclarations() {
        assertThatThrownBy(() -> VariableSet.of(ORDER_ID, TypedVariables.stringVariable("orderId")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Variable name 'orderId' is declared twice");
        assertThatThrownBy(() -> VariableSet.of(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no static VariableFactory field");
    }

    @Test
    void aSetListsAllMissingVariablesInOneMessage() {
        VariableMap map = TypedVariables.builder().set(ORDER_ID, "A-1").build();
        VariableSet set = VariableSet.of(ORDER_ID, QUANTITY, PAID);

        assertThatThrownBy(() -> set.requirePresent(map))
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessage("Required variables are missing or null in variable map: quantity, paid");
        set.requirePresent(TypedVariables.builder()
                .set(ORDER_ID, "A-1")
                .set(QUANTITY, 1)
                .set(PAID, true)
                .build());
    }
}

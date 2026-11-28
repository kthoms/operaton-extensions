/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.LINES;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER_ID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PAID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.QUANTITY;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TOTAL_CENTS;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;
import org.operaton.extensions.plugin.typedvariables.TestVariables.Status;

/** The worker side: reading what the client fetched and building the map that completes the task. */
class ExternalTaskVariablesTest {

    private final TestOrder order = new TestOrder("A-7", "bob", List.of(new TestLine("pen", 4)));

    private ExternalTask task() {
        VariableMap fetched = TypedVariables.builder()
                .set(ORDER_ID, "A-7")
                .set(ORDER, order)
                .set(LINES, order.getLines())
                .build();
        return ExternalTaskStubs.withVariables(fetched);
    }

    @Test
    void workerReadsTypedVariablesFromTheTask() {
        ExternalTaskVariables task = ExternalTaskVariables.of(task());

        assertThat(task.get(ORDER_ID)).isEqualTo("A-7");
        assertThat(task.get(ORDER)).isEqualTo(order);
        assertThat(LINES.from(task).get()).containsExactlyElementsOf(order.getLines());
        assertThat(task.getOptional(PAID)).isEmpty();
        assertThat(task.isPresent(PAID)).isFalse();
        assertThat(PAID.from(task).getOrDefault(false)).isFalse();
    }

    @Test
    void workerFailsLoudlyWhenAVariableWasNotFetched() {
        ExternalTaskVariables task = ExternalTaskVariables.of(task());

        assertThatThrownBy(() -> task.get(PAID))
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessage("Required variable 'paid' is missing or null in external task task-1 (topic price-order)");
    }

    @Test
    void anExternalTaskHasNoLocalScope() {
        ExternalTaskVariables task = ExternalTaskVariables.of(task());

        assertThatThrownBy(() -> ORDER_ID.from(task).getLocal())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("external task has no local scope");
    }

    @Test
    void workerBuildsTheCompletionVariablesWithTheSameDeclarations() {
        VariableMap result = TypedVariables.builder()
                .set(TOTAL_CENTS, 400L)
                .set(STATUS, Status.PRICED)
                .build();

        assertThat(result.keySet()).containsExactlyInAnyOrder("totalCents", "status");
        assertThat(result.getValueTyped("totalCents").getType().getName()).isEqualTo("long");
        assertThat(result.getValueTyped("status").getValue()).isEqualTo("PRICED");
        assertThat(TOTAL_CENTS.get(result)).isEqualTo(400L);
    }

    @Test
    void readerOverAnExternalTaskAndASetCheckAllVariablesAtOnce() {
        ExternalTaskVariables task = ExternalTaskVariables.of(task());
        VariableSet input = VariableSet.of(ORDER_ID, ORDER, QUANTITY, PAID);

        assertThat(task.get(ORDER_ID)).isEqualTo("A-7");
        assertThatThrownBy(() -> input.requirePresent(task))
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessage(
                        "Required variables are missing or null in external task task-1 (topic price-order): quantity, paid");
        assertThat(input.getNamesArray()).containsExactly("orderId", "order", "quantity", "paid");
    }

    @Test
    void aTypedNullValueCountsAsMissing() {
        VariableMap withNull = Variables.createVariables().putValueTyped("orderId", Variables.stringValue(null));

        assertThat(ORDER_ID.getOptional(withNull)).isEmpty();
        assertThatThrownBy(() -> ORDER_ID.get(withNull)).isInstanceOf(VariableNotFoundException.class);
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.CREATED;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.DISCOUNT;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.LINES;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER_ID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PAID;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PICKED;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.QUANTITY;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STEP;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TAGS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TOTAL_CENTS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TRACKING;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;
import org.operaton.extensions.plugin.typedvariables.TestVariables.Status;

/** Delegates and listeners reading and writing declared variables in a running embedded engine. */
class EngineTypedVariablesTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private static final Date CREATED_AT = new Date(1_700_000_000_000L);

    private final RuntimeService runtimeService = engine.getRuntimeService();
    private final TaskService taskService = engine.getTaskService();

    private VariableMap input() {
        TestOrder order = new TestOrder("A-1", "alice", List.of(new TestLine("book", 3), new TestLine("pen", 2)));
        return TypedVariables.builder()
                .set(ORDER_ID, "A-1")
                .set(QUANTITY, 5)
                .set(DISCOUNT, 0.15)
                .set(PAID, true)
                .set(CREATED, CREATED_AT)
                .set(TRACKING, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                .set(ORDER, order)
                .set(LINES, order.getLines())
                .set(TAGS, Set.of("gift"))
                .build();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void delegatesReadTypedVariablesAndWriteResults() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("typed-variables-test", input());
        String id = instance.getId();

        assertThat(ORDER_ID.from(runtimeService, id).get()).isEqualTo("A-1");
        assertThat(QUANTITY.from(runtimeService, id).get()).isEqualTo(5);
        assertThat(DISCOUNT.from(runtimeService, id).get()).isEqualTo(0.15);
        assertThat(PAID.from(runtimeService, id).get()).isTrue();
        assertThat(CREATED.from(runtimeService, id).get()).isEqualTo(CREATED_AT);
        assertThat(TRACKING.from(runtimeService, id).get()).hasToString("123e4567-e89b-12d3-a456-426614174000");
        assertThat(TAGS.from(runtimeService, id).get()).containsExactly("gift");
        assertThat(ORDER.from(runtimeService, id).get().getLines()).hasSize(2);
        // written by PriceDelegate (3 * 100 + 2 * 100) and PackDelegate
        assertThat(TOTAL_CENTS.from(runtimeService, id).get()).isEqualTo(500L);
        assertThat(STATUS.from(runtimeService, id).get()).isEqualTo(Status.PACKED);
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void missingRequiredVariableFailsTheDelegateWithAClearMessage() {
        VariableMap withoutOrder = TypedVariables.builder().set(ORDER_ID, "A-2").build();

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("typed-variables-test", withoutOrder))
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessageContaining("Required variable 'order' is missing or null in execution")
                .hasMessageContaining("activity price");
        // the failed command rolled back: nothing was started
        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void localVariablesLiveInTheScopeThatSetThem() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("typed-variables-test", input());
        Task wait = taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();

        // set by the sub process listener, read and extended by PackDelegate in the same scope
        assertThat(STEP.from(runtimeService, wait.getExecutionId()).getLocal()).isEqualTo("packing");
        assertThat(PICKED.from(runtimeService, wait.getExecutionId()).getLocal())
                .isEqualTo("packing".length());
        // the process instance scope does not see them
        assertThat(STEP.from(runtimeService, instance.getId()).getOptional()).isEmpty();
        assertThatThrownBy(() -> STEP.from(runtimeService, instance.getId()).getLocal())
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessageContaining("Required local variable 'step' is missing or null");
        // the scope chain makes the global ones visible from the task
        assertThat(ORDER_ID.fromTask(taskService, wait.getId()).get()).isEqualTo("A-1");
        // a task has its own local scope, which is empty here
        assertThat(STEP.fromTask(taskService, wait.getId()).getLocalOptional()).isEmpty();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void variablesCanBeWrittenAndRemovedThroughTheServices() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("typed-variables-test", input());
        String id = instance.getId();

        PAID.on(runtimeService, id).set(false);
        assertThat(PAID.from(runtimeService, id).get()).isFalse();

        PAID.on(runtimeService, id).remove();
        assertThat(PAID.from(runtimeService, id).isPresent()).isFalse();
        assertThatThrownBy(() -> PAID.from(runtimeService, id).get())
                .isInstanceOf(VariableNotFoundException.class)
                .hasMessageContaining("'paid'")
                .hasMessageContaining("execution " + id);

        Task wait = taskService.createTaskQuery().processInstanceId(id).singleResult();
        TOTAL_CENTS.onTask(taskService, wait.getId()).set(1L);
        assertThat(TOTAL_CENTS.from(runtimeService, id).get()).isEqualTo(1L);
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void valueOfAnotherTypeFailsLoudly() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("typed-variables-test", input());
        runtimeService.setVariable(instance.getId(), "quantity", "five");

        assertThatThrownBy(() -> QUANTITY.from(runtimeService, instance.getId()).get())
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining(
                        "Variable 'quantity' is declared as Integer but holds a string value of Java type java.lang.String");
        // an optional read does not hide a type mismatch either
        assertThatThrownBy(() -> QUANTITY.from(runtimeService, instance.getId()).getOptional())
                .isInstanceOf(WrongVariableTypeException.class);
    }
}

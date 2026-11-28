/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.intVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.listVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.longVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.stringVariable;
import static org.operaton.extensions.plugin.typedvariables.test.TypedVariablesAssertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.extensions.plugin.typedvariables.TypedVariables;
import org.operaton.extensions.plugin.typedvariables.VariableFactory;
import org.operaton.extensions.plugin.typedvariables.VariableSet;

/** The assertions on a process engine extension, a historic instance, a map and a scope. */
class AssertionsTest {

    static final VariableFactory<String> ORDER_ID = stringVariable("orderId");
    static final VariableFactory<Long> TOTAL_CENTS = longVariable("totalCents");
    static final VariableFactory<Integer> QUANTITY = intVariable("quantity");
    static final VariableFactory<List<String>> ERRORS = listVariable("errors", String.class);
    static final VariableFactory<String> STEP = stringVariable("step");

    /** Puts a variable into the sub process scope. */
    public static class LocalListener implements ExecutionListener {
        @Override
        public void notify(DelegateExecution execution) {
            STEP.setLocal(execution, "packing");
        }
    }

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private ProcessInstance start() {
        return engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "assertions-test",
                        TypedVariables.builder()
                                .set(ORDER_ID, "A-1")
                                .set(TOTAL_CENTS, 1999L)
                                .set(ERRORS, List.of("late", "damaged"))
                                .build());
    }

    @Test
    @Deployment(resources = "assertions-test.bpmn")
    void processInstanceAssertionsFindTheEngineThemselves() {
        ProcessInstance instance = start();

        assertThat(instance)
                .hasVariable(ORDER_ID)
                .hasVariable(ORDER_ID, "A-1")
                .hasVariable(TOTAL_CENTS, 1999L)
                .hasVariableSatisfying(ERRORS, errors -> assertThat(errors).containsExactly("late", "damaged"))
                .hasNoVariable(QUANTITY)
                .hasAllVariables(VariableSet.of(ORDER_ID, TOTAL_CENTS, ERRORS));
        assertThat(instance).variable(TOTAL_CENTS).isEqualTo(1999L);
        assertThat(instance)
                .variable(TOTAL_CENTS, InstanceOfAssertFactories.LONG)
                .isGreaterThan(1000L);
        assertThat(instance)
                .variable(ERRORS, InstanceOfAssertFactories.list(String.class))
                .hasSize(2);
        assertThat(engine, instance).hasVariable(ORDER_ID);
    }

    @Test
    @Deployment(resources = "assertions-test.bpmn")
    void localVariablesAreAssertedOnTheExecutionThatOwnsThem() {
        ProcessInstance instance = start();
        Task wait = engine.getTaskService().createTaskQuery().singleResult();
        org.operaton.bpm.engine.runtime.Execution inSub = engine.getRuntimeService()
                .createExecutionQuery()
                .executionId(wait.getExecutionId())
                .singleResult();

        assertThat(inSub).hasLocalVariable(STEP, "packing").hasVariable(ORDER_ID, "A-1");
        assertThat(instance).hasNoLocalVariable(STEP).hasNoVariable(STEP);
    }

    @Test
    @Deployment(resources = "assertions-test.bpmn")
    void failureMessagesNameTheVariableAndWhereItWasLookedUp() {
        ProcessInstance instance = start();

        assertThatThrownBy(() -> assertThat(instance).hasVariable(QUANTITY))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting execution " + instance.getId()
                        + " to have variable 'quantity' but it is missing or null");
        assertThatThrownBy(() -> assertThat(instance).hasVariable(TOTAL_CENTS, 5L))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("to have variable 'totalCents' with value")
                .hasMessageContaining("<5>")
                .hasMessageContaining("<1999>");
        assertThatThrownBy(() -> assertThat(instance).hasNoVariable(ORDER_ID))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("to have no variable 'orderId' but it was")
                .hasMessageContaining("<A-1>");
        assertThatThrownBy(() -> assertThat(instance).hasAllVariables(VariableSet.of(ORDER_ID, QUANTITY, STEP)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("these are missing or null: [quantity, step]");
    }

    @Test
    @Deployment(resources = "assertions-test.bpmn")
    void aValueOfAnotherTypeFailsTheAssertionWithTheCause() {
        ProcessInstance instance = start();
        engine.getRuntimeService().setVariable(instance.getId(), "quantity", "many");

        assertThatThrownBy(() -> assertThat(instance).hasVariable(QUANTITY))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Variable 'quantity' is declared as Integer")
                .hasCauseInstanceOf(org.operaton.extensions.plugin.typedvariables.WrongVariableTypeException.class);
    }

    @Test
    @Deployment(resources = "assertions-test.bpmn")
    void historicProcessInstanceAssertionsWorkAfterTheProcessEnded() {
        ProcessInstance instance = start();
        Task wait = engine.getTaskService().createTaskQuery().singleResult();
        engine.getTaskService().complete(wait.getId());
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count())
                .isZero();

        HistoricProcessInstance historic = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult();

        assertThat(historic)
                .hasVariable(ORDER_ID, "A-1")
                .hasVariable(TOTAL_CENTS, 1999L)
                .hasNoVariable(QUANTITY);
        // the sub process local is not a variable of the process instance
        assertThat(historic).hasNoVariable(STEP);
    }

    @Test
    void mapAndScopeAssertionsNeedNoEngine() {
        VariableMap completion = TypedVariables.builder()
                .set(ORDER_ID, "A-2")
                .set(ERRORS, new ArrayList<>(List.of("x")))
                .build();

        assertThat(completion).hasVariable(ORDER_ID, "A-2").hasNoVariable(TOTAL_CENTS);
        assertThatThrownBy(() -> assertThat(completion).hasLocalVariable(ORDER_ID))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anAssertionOnNothingFails() {
        assertThatThrownBy(() -> new VariablesAssert(null).hasVariable(ORDER_ID))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void theShortFormRefusesToGuessBetweenTwoRegisteredEngines() {
        ProcessEngine second = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
                .setProcessEngineName("second")
                .setJdbcUrl("jdbc:h2:mem:second;DB_CLOSE_DELAY=1000")
                .setJobExecutorActivate(false)
                .buildProcessEngine();
        try {
            assertThatThrownBy(
                            () -> TypedVariablesAssertions.assertThat((org.operaton.bpm.engine.runtime.Execution) null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Expected exactly one registered process engine but found 2")
                    .hasMessageContaining("assertThat(engine, processInstance)");
        } finally {
            second.close();
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.LINES;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TAGS;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;

/** Objects, lists and sets stored as JSON through Spin (the engine of this test registers the Spin plugin). */
class ObjectVariablesTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private final RuntimeService runtimeService = engine.getRuntimeService();
    private final TestOrder order = new TestOrder("A-1", "alice", List.of(new TestLine("book", 3)));

    @Test
    void autoFormatPicksJsonWhenSpinIsPresent() {
        assertThat(ObjectVariablesTestSupport.spinPresent()).isTrue();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void anObjectRoundTripsAsJson() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder()
                        .set(ORDER, order)
                        .set(LINES, order.getLines())
                        .set(TAGS, Set.of("gift", "fragile"))
                        .build());

        ObjectValue stored = runtimeService.getVariableTyped(instance.getId(), "order", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(stored.getValueSerialized()).contains("\"id\":\"A-1\"").contains("\"customer\":\"alice\"");
        assertThat(stored.getObjectTypeName()).isEqualTo(TestOrder.class.getName());

        assertThat(ORDER.from(runtimeService, instance.getId()).get()).isEqualTo(order);
        assertThat(LINES.from(runtimeService, instance.getId()).get()).containsExactlyElementsOf(order.getLines());
        assertThat(TAGS.from(runtimeService, instance.getId()).get()).containsExactlyInAnyOrder("gift", "fragile");
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void aListStoredAsJsonKeepsItsElementType() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder()
                        .set(ORDER, order)
                        .set(LINES, order.getLines())
                        .build());

        ObjectValue stored = runtimeService.getVariableTyped(instance.getId(), "lines", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(stored.getObjectTypeName()).isEqualTo("java.util.ArrayList<" + TestLine.class.getName() + ">");
        assertThat(stored.getValueSerialized()).isEqualTo("[{\"sku\":\"book\",\"quantity\":3}]");
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void anEmptyListRoundTrips() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder().set(ORDER, order).set(LINES, List.of()).build());

        assertThat(LINES.from(runtimeService, instance.getId()).get()).isEmpty();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void javaSerializationCanBeRequestedExplicitly() {
        VariableFactory<TestOrder> javaOrder =
                TypedVariables.customVariable("javaOrder", TestOrder.class, SerializationFormat.JAVA);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder()
                        .set(ORDER, order)
                        .set(LINES, order.getLines())
                        .set(javaOrder, order)
                        .build());

        ObjectValue stored = runtimeService.getVariableTyped(instance.getId(), "javaOrder", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
        assertThat(javaOrder.from(runtimeService, instance.getId()).get()).isEqualTo(order);
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void aJsonObjectThatIsNotOfTheDeclaredTypeFailsLoudly() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder()
                        .set(ORDER, order)
                        .set(LINES, order.getLines())
                        .build());
        VariableFactory<TestLine> wrong = TypedVariables.customVariable("order", TestLine.class);

        assertThatThrownBy(() -> wrong.from(runtimeService, instance.getId()).get())
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining("Variable 'order' is declared as " + TestLine.class.getName())
                .hasMessageContaining("Java type " + TestOrder.class.getName());
    }

    @Test
    void elementsOfTheWrongTypeAreRejectedOnWrite() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        VariableFactory<List> raw = (VariableFactory) LINES;
        var map = Variables.createVariables();

        assertThatThrownBy(() -> raw.set(map, List.of("not a line")))
                .isInstanceOf(WrongVariableTypeException.class)
                .hasMessageContaining("declared as List<TestLine>")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void declaringJavaSerializationForANonSerializableClassFailsAtDeclaration() {
        assertThatThrownBy(() -> TypedVariables.customVariable("x", Runnable.class, SerializationFormat.JAVA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not Serializable");
    }
}

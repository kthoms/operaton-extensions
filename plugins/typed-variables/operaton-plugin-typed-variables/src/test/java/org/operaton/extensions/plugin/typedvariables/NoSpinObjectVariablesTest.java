/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.LINES;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;

/**
 * Runs in its own Surefire execution with the Spin artifacts removed from the classpath: objects fall back to Java
 * serialization and requesting JSON fails at declaration.
 */
class NoSpinObjectVariablesTest {

    @RegisterExtension
    static ProcessEngineExtension engine =
            ProcessEngineExtension.builder().configurationResource("operaton-no-spin.cfg.xml");

    private final RuntimeService runtimeService = engine.getRuntimeService();
    private final TestOrder order = new TestOrder("A-1", "alice", List.of(new TestLine("book", 3)));

    @Test
    void spinIsReallyAbsent() {
        assertThat(ObjectVariablesTestSupport.spinPresent()).isFalse();
    }

    @Test
    @Deployment(resources = "typed-variables-test.bpmn")
    void autoFormatFallsBackToJavaSerialization() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "typed-variables-test",
                TypedVariables.builder()
                        .set(ORDER, order)
                        .set(LINES, order.getLines())
                        .build());

        ObjectValue stored = runtimeService.getVariableTyped(instance.getId(), "order", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo(Variables.SerializationDataFormats.JAVA.getName());
        assertThat(ORDER.from(runtimeService, instance.getId()).get()).isEqualTo(order);
        assertThat(LINES.from(runtimeService, instance.getId()).get()).containsExactlyElementsOf(order.getLines());
    }

    @Test
    void jsonCannotBeRequestedWithoutSpin() {
        assertThatThrownBy(() -> TypedVariables.customVariable("x", TestOrder.class, SerializationFormat.JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operaton-engine-plugin-spin");
    }

    @Test
    void aClassThatIsNotSerializableCannotUseTheFallback() {
        assertThatThrownBy(() -> TypedVariables.customVariable("x", Runnable.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not Serializable")
                .hasMessageContaining("operaton-spin-dataformat-json-jackson");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.value.ObjectValue;

/** The JavaDelegate alternatives inside a real engine. */
class LlmDelegateEngineTest {

    static final LlmStubServer STUB = new LlmStubServer();

    // not final: ProcessEngineExtension injects the engine into ProcessEngine fields
    static ProcessEngine ENGINE = TestEngines.build("llm-delegate-test", TestEngines.plugin(STUB));

    @RegisterExtension
    static ProcessEngineExtension extension =
            ProcessEngineExtension.builder().useProcessEngine(ENGINE).build();

    @AfterAll
    static void stop() {
        STUB.close();
        ENGINE.close();
    }

    @Test
    @Deployment(resources = "bpmn/llm-delegates.bpmn")
    void delegatesWriteResultAndTokenUsage() {
        STUB.reset();
        STUB.replyText("Hello Bob!")
                .replyText("{\"city\":\"Berlin\"}")
                .replyToolCall("c1", "read_process_variable", "{\"name\":\"orderId\"}")
                .replyText("Order 7 is known.");
        RuntimeService runtimeService = ENGINE.getRuntimeService();
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "llm-delegates", Map.of("name", "Bob", "letter", "Greetings from Berlin", "orderId", "7"));

        assertThat(runtimeService.getVariable(instance.getId(), "greeting")).isEqualTo("Hello Bob!");
        assertThat(runtimeService.getVariable(instance.getId(), "greetingTotalTokens"))
                .isEqualTo(27L);
        assertThat(runtimeService.getVariable(instance.getId(), "greetingInputTokens"))
                .isEqualTo(20L);
        ObjectValue address = runtimeService.getVariableTyped(instance.getId(), "address");
        assertThat(address.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(address.getValue()).isEqualTo(Map.of("city", "Berlin"));
        assertThat(runtimeService.getVariable(instance.getId(), "status")).isEqualTo("Order 7 is known.");
        assertThat(runtimeService.getVariable(instance.getId(), "statusTotalTokens"))
                .isEqualTo(54L);
        assertThat(STUB.requests().get(0).body().get("temperature").asDouble()).isEqualTo(0.5);
    }
}

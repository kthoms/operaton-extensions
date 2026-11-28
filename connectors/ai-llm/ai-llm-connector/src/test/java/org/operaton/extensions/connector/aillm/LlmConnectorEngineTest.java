/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.connector.aillm.tool.BeanMethodTool;

/** The connectors inside a real engine (H2), with Connect and Spin, against the stub server. */
class LlmConnectorEngineTest {

    static final LlmStubServer STUB = new LlmStubServer();

    /** Tool target. */
    public static class Orders {
        public String status(String orderId) {
            return "Order " + orderId + " is in transit";
        }
    }

    // not final: ProcessEngineExtension injects the engine into ProcessEngine fields
    static ProcessEngine ENGINE = TestEngines.build(
            "llm-connector-test",
            TestEngines.plugin(STUB)
                    .addTool(BeanMethodTool.of(
                            "order_status", "Returns the status of an order", new Orders(), "status")));

    @RegisterExtension
    static ProcessEngineExtension extension =
            ProcessEngineExtension.builder().useProcessEngine(ENGINE).build();

    RuntimeService runtimeService;
    TaskService taskService;

    @AfterAll
    static void stop() {
        STUB.close();
        ENGINE.close();
    }

    @BeforeEach
    void setUp() {
        STUB.reset();
        runtimeService = ENGINE.getRuntimeService();
        taskService = ENGINE.getTaskService();
    }

    @Test
    @Deployment(resources = "bpmn/llm-prompt.bpmn")
    void promptRendersProcessVariablesAndMapsTheAnswer() {
        STUB.replyText("Printer jams since Monday.");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "llm-prompt",
                Variables.putValue("ticketText", "My printer jams all the time since Monday!")
                        .putValue(
                                "customer",
                                Variables.objectValue(new java.util.HashMap<>(Map.of("name", "ACME")))
                                        .serializationDataFormat("application/json")
                                        .create())
                        .putValue("maxTokens", 80));

        assertThat(runtimeService.getVariable(instance.getId(), "summary")).isEqualTo("Printer jams since Monday.");
        assertThat(runtimeService.getVariable(instance.getId(), "summaryTokens"))
                .isEqualTo(27L);
        assertThat(runtimeService.getVariable(instance.getId(), "summaryModel")).isEqualTo("stub-model");
        assertThat(runtimeService.getVariable(instance.getId(), "text"))
                .as("connector outputs stay local to the connector")
                .isNull();
        var body = STUB.lastRequest().body();
        assertThat(body.get("messages").get(0).get("content").asText())
                .isEqualTo("You summarize support tickets of ACME.");
        assertThat(body.get("messages").get(1).get("content").asText())
                .isEqualTo("Summarize: My printer jams all the time since Monday!");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(80);
        assertThat(taskService
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .singleResult()
                        .getTaskDefinitionKey())
                .isEqualTo("review");
    }

    @Test
    @Deployment(resources = "bpmn/llm-extract.bpmn")
    void extractResultDrivesTheGateway() {
        STUB.replyText("{\"category\":\"billing\",\"urgency\":\"high\"}");
        ProcessInstance urgent = runtimeService.startProcessInstanceByKey(
                "llm-extract", Map.of("emailText", "You charged me twice! Fix it today."));

        assertThat(runtimeService.getVariable(urgent.getId(), "classification"))
                .isEqualTo(Map.of("category", "billing", "urgency", "high"));
        ObjectValue stored = runtimeService.getVariableTyped(urgent.getId(), "classification", false);
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(runtimeService.getVariable(urgent.getId(), "classificationJson"))
                .isEqualTo("{\"category\":\"billing\",\"urgency\":\"high\"}");
        assertThat(taskService
                        .createTaskQuery()
                        .processInstanceId(urgent.getId())
                        .singleResult()
                        .getTaskDefinitionKey())
                .isEqualTo("escalate");

        STUB.replyText("not json").replyText("{\"category\":\"other\",\"urgency\":\"low\"}");
        ProcessInstance normal =
                runtimeService.startProcessInstanceByKey("llm-extract", Map.of("emailText", "Thanks for the help."));
        assertThat(runtimeService.getVariable(normal.getId(), "attempts")).isEqualTo(2);
        assertThat(taskService
                        .createTaskQuery()
                        .processInstanceId(normal.getId())
                        .singleResult()
                        .getTaskDefinitionKey())
                .isEqualTo("handle");
    }

    @Test
    @Deployment(resources = "bpmn/llm-extract.bpmn")
    void invalidOutputFailsTheTaskAndRollsBack() {
        STUB.replyText("{\"category\":\"spam\"}").replyText("{\"category\":\"spam\"}");

        assertThatThrownBy(
                        () -> runtimeService.startProcessInstanceByKey("llm-extract", Map.of("emailText", "Buy now")))
                .isInstanceOf(LlmOutputValidationException.class)
                .hasMessageContaining("after 2 attempt(s)");
        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    }

    @Test
    @Deployment(resources = "bpmn/llm-extract.bpmn")
    void missingTemplateVariableFailsBeforeAnyModelCall() {
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("llm-extract"))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("emailText");
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    @Deployment(resources = "bpmn/llm-agent.bpmn")
    void agentUsesTheVariableToolAndTheBeanTool() {
        STUB.replyToolCall("c1", "read_process_variable", "{\"name\":\"orderId\"}")
                .replyToolCall("c2", "order_status", "{\"orderId\":\"A-42\"}")
                .replyText("Your order A-42 is in transit.");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "llm-agent", Map.of("question", "Where is my order?", "orderId", "A-42"));

        assertThat(runtimeService.getVariable(instance.getId(), "answer")).isEqualTo("Your order A-42 is in transit.");
        assertThat(runtimeService.getVariable(instance.getId(), "agentIterations"))
                .isEqualTo(3);
        assertThat(runtimeService.getVariable(instance.getId(), "agentToolCalls"))
                .isEqualTo(2);
        var messages = STUB.lastRequest().body().get("messages");
        assertThat(messages.get(2).get("content").asText()).isEqualTo("A-42");
        assertThat(messages.get(4).get("content").asText()).isEqualTo("Order A-42 is in transit");
    }

    @Test
    @Deployment(resources = "bpmn/llm-agent.bpmn")
    void agentIterationLimitFailsTheTask() {
        STUB.replyToolCall("c1", "order_status", "{\"orderId\":\"1\"}")
                .replyToolCall("c2", "order_status", "{\"orderId\":\"2\"}")
                .replyToolCall("c3", "order_status", "{\"orderId\":\"3\"}");

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
                        "llm-agent", Map.of("question", "Loop forever", "orderId", "1")))
                .isInstanceOf(LlmAgentIterationLimitException.class);
        assertThat(STUB.requests()).hasSize(3);
    }

    @Test
    @Deployment(resources = "bpmn/llm-agent.bpmn")
    void longAnswersAreStoredAsJsonObjectValues() {
        String longAnswer = "x".repeat(5000);
        STUB.replyText(longAnswer);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "llm-agent", Map.of("question", "Write a lot", "orderId", "1"));

        ObjectValue stored = runtimeService.getVariableTyped(instance.getId(), "answer");
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(stored.getValue()).isEqualTo(longAnswer);
    }

    @Test
    @Deployment(resources = "bpmn/llm-api-key.bpmn")
    void apiKeysAreRefusedAsProcessData() {
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("llm-api-key", Map.of("key", "sk-leak")))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("API keys are configured per model")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk-leak"));
        assertThat(STUB.requests()).isEmpty();
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.aillm.tool.BeanMethodTool;
import org.operaton.extensions.connector.aillm.tool.ToolRefusedException;

/** The service against the stub server: request shape, retries, the agent loop and its guards. */
class LlmServiceTest {

    static final LlmStubServer STUB = new LlmStubServer();

    static final String SCHEMA = """
            {"type":"object","required":["category","urgency"],"additionalProperties":false,
             "properties":{"category":{"enum":["billing","technical","other"]},
                           "urgency":{"enum":["low","high"]}}}""";

    /** A tool target; records its calls. */
    public static class OrderService {
        final List<String> calls = new ArrayList<>();

        public String findOrderStatus(String orderId) {
            calls.add(orderId);
            if (orderId.startsWith("X")) {
                throw new ToolRefusedException("Order ids start with A");
            }
            return "shipped";
        }

        public int failing(int value) {
            throw new IllegalStateException("database down");
        }
    }

    final OrderService orders = new OrderService();

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    LlmService service(String structuredOutput) {
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider("openai");
        config.setBaseUrl(STUB.baseUrl());
        config.setModel("gpt-test");
        config.setApiKey("sk-secret-123");
        config.setTemperature(0.2);
        config.setMaxRetries(0);
        config.setStructuredOutput(structuredOutput);
        Map<String, LlmModelConfig> models = new LinkedHashMap<>();
        models.put("default", config);
        return new LlmService(
                models,
                "default",
                2,
                3,
                List.of(
                        BeanMethodTool.of("order_status", "Looks up the status of an order", orders, "findOrderStatus"),
                        BeanMethodTool.of("failing", "Always fails", orders, "failing")));
    }

    @Test
    void promptRendersTemplatesAndSendsTheConfiguredModel() {
        STUB.replyText("Hello Alice");
        LlmTextResult result = service(null)
                .prompt(
                        new LlmPromptRequest(null, "You greet {{who.role}}s.", "Greet {{who.name}}.", null, 50),
                        VariableSource.of(Map.of("who", Map.of("name", "Alice", "role", "customer"))));

        assertThat(result.text()).isEqualTo("Hello Alice");
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.usage()).isEqualTo(new LlmUsage(20L, 7L, 27L, 1));
        LlmStubServer.Recorded request = STUB.lastRequest();
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer sk-secret-123");
        JsonNode body = request.body();
        assertThat(body.get("model").asText()).isEqualTo("gpt-test");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(50);
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("You greet customers.");
        assertThat(body.get("messages").get(1).get("content").asText()).isEqualTo("Greet Alice.");
    }

    @Test
    void unknownPlaceholderFailsBeforeAnyCall() {
        assertThatThrownBy(() -> service(null).prompt(LlmPromptRequest.of("Hi {{nobody}}"), VariableSource.none()))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("nobody");
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    void unknownModelFails() {
        assertThatThrownBy(() -> service(null)
                        .prompt(new LlmPromptRequest("big", null, "Hi", null, null), VariableSource.none()))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("Model 'big' is not configured");
    }

    @Test
    void providerErrorIsReportedWithoutTheApiKey() {
        STUB.replyError(401, "{\"error\":{\"message\":\"invalid key\"}}");
        assertThatThrownBy(() -> service(null).prompt(LlmPromptRequest.of("Hi"), VariableSource.none()))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("Call of model 'default'")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk-secret-123"));
    }

    @Test
    void extractReturnsTheValidatedDocumentInJsonMode() {
        STUB.replyText("```json\n{\"category\":\"billing\",\"urgency\":\"high\"}\n```");
        LlmExtractResult result = service(null)
                .extract(LlmPromptRequest.of("Classify: refund please"), SCHEMA, null, VariableSource.none());

        assertThat(result.value()).isEqualTo(Map.of("category", "billing", "urgency", "high"));
        assertThat(result.json()).isEqualTo("{\"category\":\"billing\",\"urgency\":\"high\"}");
        assertThat(result.attempts()).isEqualTo(1);
        JsonNode body = STUB.lastRequest().body();
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(body.get("messages").get(0).get("content").asText()).contains("\"enum\":[\"billing\"");
    }

    @Test
    void extractRetriesWithTheProblemsAfterInvalidJson() {
        STUB.replyText("Sure! The category is billing.")
                .replyText("{\"category\":\"refunds\",\"urgency\":\"high\"}")
                .replyText("{\"category\":\"billing\",\"urgency\":\"low\"}");
        LlmExtractResult result =
                service(null).extract(LlmPromptRequest.of("Classify"), SCHEMA, null, VariableSource.none());

        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.value()).isEqualTo(Map.of("category", "billing", "urgency", "low"));
        assertThat(result.usage()).isEqualTo(new LlmUsage(60L, 21L, 81L, 3));
        List<LlmStubServer.Recorded> requests = STUB.requests();
        assertThat(LlmStubServer.roles(requests.get(1))).containsExactly("system", "user", "assistant", "user");
        assertThat(requests.get(1).body().get("messages").get(3).get("content").asText())
                .contains("not valid JSON");
        assertThat(requests.get(2).body().get("messages").get(5).get("content").asText())
                .contains("category");
    }

    @Test
    void extractFailsLoudlyWhenRetriesAreExhausted() {
        STUB.replyText("no").replyText("{\"category\":\"billing\"}");
        assertThatThrownBy(
                        () -> service(null).extract(LlmPromptRequest.of("Classify"), SCHEMA, 1, VariableSource.none()))
                .isInstanceOf(LlmOutputValidationException.class)
                .hasMessageContaining("after 2 attempt(s)")
                .hasMessageContaining("urgency");
        assertThat(STUB.requests()).hasSize(2);
    }

    @Test
    void extractRejectsAnInvalidSchemaAndUnboundedRetries() {
        assertThatThrownBy(
                        () -> service(null).extract(LlmPromptRequest.of("x"), "{not json", null, VariableSource.none()))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("jsonSchema is not valid JSON");
        assertThatThrownBy(() -> service(null).extract(LlmPromptRequest.of("x"), SCHEMA, 11, VariableSource.none()))
                .hasMessageContaining("retries must be between 0 and 10");
        assertThatThrownBy(() -> service(null)
                        .extract(
                                LlmPromptRequest.of("x"),
                                "{\"$ref\":\"https://example.org/schema.json\"}",
                                null,
                                VariableSource.none()))
                .isInstanceOf(AiLlmException.class);
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    void extractSendsTheSchemaInJsonSchemaMode() {
        STUB.replyText("{\"category\":\"other\",\"urgency\":\"low\"}");
        service("json-schema").extract(LlmPromptRequest.of("Classify"), SCHEMA, null, VariableSource.none());
        JsonNode format = STUB.lastRequest().body().get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("schema").get("required").toString())
                .isEqualTo("[\"category\",\"urgency\"]");
    }

    @Test
    void agentCallsToolsUntilItAnswers() {
        STUB.replyToolCall("call_1", "order_status", "{\"orderId\":\"A-17\"}").replyText("Order A-17 has shipped.");
        LlmAgentResult result = service(null)
                .agent(
                        LlmPromptRequest.of("Where is order A-17?"),
                        List.of("order_status"),
                        List.of(),
                        null,
                        VariableSource.none(),
                        null);

        assertThat(result.text()).isEqualTo("Order A-17 has shipped.");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage().totalTokens()).isEqualTo(54L);
        assertThat(orders.calls).containsExactly("A-17");
        JsonNode first = STUB.requests().get(0).body();
        assertThat(first.get("tools")).hasSize(1);
        assertThat(first.get("tools").get(0).get("function").get("name").asText())
                .isEqualTo("order_status");
        JsonNode second = STUB.requests().get(1).body().get("messages");
        assertThat(second.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(second.get(2).get("content").asText()).isEqualTo("shipped");
    }

    @Test
    void agentTellsTheModelAboutUnknownToolsAndRefusals() {
        STUB.replyToolCall("c1", "delete_everything", "{}")
                .replyToolCall("c2", "order_status", "{\"orderId\":\"X-1\"}")
                .replyText("I could not find it.");
        LlmAgentResult result = service(null)
                .agent(
                        LlmPromptRequest.of("Where is my order?"),
                        List.of("order_status"),
                        List.of(),
                        null,
                        VariableSource.none(),
                        null);

        assertThat(result.iterations()).isEqualTo(3);
        List<LlmStubServer.Recorded> requests = STUB.requests();
        assertThat(requests.get(1).body().get("messages").get(2).get("content").asText())
                .contains("There is no tool named 'delete_everything'")
                .contains("order_status");
        assertThat(requests.get(2).body().get("messages").get(4).get("content").asText())
                .isEqualTo("Order ids start with A");
    }

    @Test
    void agentStopsAtMaxIterations() {
        STUB.replyToolCall("c1", "order_status", "{\"orderId\":\"A-1\"}")
                .replyToolCall("c2", "order_status", "{\"orderId\":\"A-2\"}");
        assertThatThrownBy(() -> service(null)
                        .agent(
                                LlmPromptRequest.of("Loop"),
                                List.of("order_status"),
                                List.of(),
                                2,
                                VariableSource.none(),
                                null))
                .isInstanceOf(LlmAgentIterationLimitException.class)
                .hasMessageContaining("maxIterations=2");
        assertThat(STUB.requests()).hasSize(2);
    }

    @Test
    void agentFailsWhenAToolFails() {
        STUB.replyToolCall("c1", "failing", "{\"value\":1}");
        assertThatThrownBy(() -> service(null)
                        .agent(
                                LlmPromptRequest.of("Go"),
                                List.of("failing"),
                                List.of(),
                                null,
                                VariableSource.none(),
                                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
    }

    @Test
    void agentOnlyOffersEnabledRegisteredTools() {
        assertThatThrownBy(() -> service(null)
                        .agent(
                                LlmPromptRequest.of("Go"),
                                List.of("shell"),
                                List.of(),
                                null,
                                VariableSource.none(),
                                null))
                .isInstanceOf(AiLlmException.class)
                .hasMessageContaining("Tool 'shell' is not registered");
    }

    @Test
    void agentReadsOnlyAllowedVariables() {
        STUB.replyToolCall("c1", "read_process_variable", "{\"name\":\"salary\"}")
                .replyToolCall("c2", "read_process_variable", "{\"name\":\"customer\"}")
                .replyText("Done");
        VariableSource process = VariableSource.of(Map.of("customer", Map.of("name", "Bob"), "salary", 100_000));
        service(null)
                .agent(
                        LlmPromptRequest.of("Who?"),
                        List.of(),
                        List.of("customer"),
                        null,
                        VariableSource.none(),
                        process);

        List<LlmStubServer.Recorded> requests = STUB.requests();
        assertThat(requests.get(0)
                        .body()
                        .get("tools")
                        .get(0)
                        .get("function")
                        .get("parameters")
                        .toString())
                .contains("\"enum\":[\"customer\"]");
        assertThat(requests.get(1).body().get("messages").get(2).get("content").asText())
                .contains("may not be read");
        assertThat(requests.get(2).body().get("messages").get(4).get("content").asText())
                .isEqualTo("{\"name\":\"Bob\"}");
    }

    @Test
    void anthropicProviderUsesItsHeaderAndApi() {
        STUB.replyAnthropicText("Bonjour");
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider("anthropic");
        config.setBaseUrl(STUB.baseUrl() + "/");
        config.setModel("claude-test");
        config.setApiKey("ant-secret");
        config.setMaxTokens(100);
        LlmService service = new LlmService(Map.of("claude", config), "claude", 0, 1, List.of());

        LlmTextResult result = service.prompt(
                new LlmPromptRequest(null, "Be brief.", "Say hello in French", null, null), VariableSource.none());

        assertThat(result.text()).isEqualTo("Bonjour");
        assertThat(result.usage().inputTokens()).isEqualTo(11L);
        LlmStubServer.Recorded request = STUB.lastRequest();
        assertThat(request.path()).isEqualTo("/v1/messages");
        assertThat(request.header("x-api-key")).isEqualTo("ant-secret");
        assertThat(request.body().get("system").toString()).contains("Be brief.");
        assertThat(request.body().get("max_tokens").asInt()).isEqualTo(100);
    }

    @Test
    void azureProviderSendsTheKeyInTheApiKeyHeader() {
        STUB.replyText("ok");
        LlmModelConfig config = new LlmModelConfig();
        config.setProvider("azure-openai");
        config.setBaseUrl(STUB.baseUrl());
        config.setModel("my-deployment");
        config.setApiKey("azure-secret");
        new LlmService(Map.of("azure", config), "azure", 0, 1, List.of())
                .prompt(LlmPromptRequest.of("Hi"), VariableSource.none());

        LlmStubServer.Recorded request = STUB.lastRequest();
        assertThat(request.header("api-key")).isEqualTo("azure-secret");
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.body().get("model").asText()).isEqualTo("my-deployment");
    }
}

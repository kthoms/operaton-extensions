/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the triage process end to end on PostgreSQL. The model is a local fake of the OpenAI chat
 * completions API that classifies by keywords, so the test runs offline; everything else (engine,
 * Connect, the llm-extract connector, LangChain4j, JSON Schema validation, Spin JSON) is real.
 */
@SpringBootTest
@Testcontainers
class SupportTriageIT {

    static final String DOUBLE_CHARGE =
            "{\"category\":\"billing\",\"urgency\":\"high\",\"sentiment\":\"negative\",\"summary\":\"Charged twice for order 1042\"}";
    static final String INVOICE =
            "{\"category\":\"billing\",\"urgency\":\"normal\",\"sentiment\":\"neutral\",\"summary\":\"Needs a copy of an invoice\"}";
    static final String FEATURE =
            "{\"category\":\"other\",\"urgency\":\"low\",\"sentiment\":\"positive\",\"summary\":\"Asks for a dark mode\"}";

    static final FakeChatCompletionsServer MODEL = start();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void model(DynamicPropertyRegistry registry) {
        registry.add("operaton.extensions.connector.ai-llm.models.triage.base-url", MODEL::baseUrl);
        registry.add("operaton.extensions.connector.ai-llm.models.triage.model", () -> "fake-triage");
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    /** The fake model: classifies by keywords; answers prose first for the dark-mode e-mail. */
    static FakeChatCompletionsServer start() {
        try {
            return new FakeChatCompletionsServer(request -> {
                JsonNode messages = request.get("messages");
                String email = messages.get(1).get("content").asText();
                boolean retry = messages.size() > 2;
                if (email.contains("charged twice")) {
                    return DOUBLE_CHARGE;
                }
                if (email.contains("invoice")) {
                    return "```json\n" + INVOICE + "\n```";
                }
                if (email.contains("dark mode")) {
                    return retry ? FEATURE : "Sounds like a feature request to me!";
                }
                return "{\"category\":\"spam\"}";
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ProcessInstance receive(String subject, String body) {
        return runtimeService.startProcessInstanceByKey(
                "support-email-triage", Map.of("sender", "customer@example.org", "subject", subject, "body", body));
    }

    Task awaitTask(ProcessInstance instance) {
        return await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> taskService
                                .createTaskQuery()
                                .processInstanceId(instance.getId())
                                .singleResult(),
                        task -> task != null);
    }

    @Test
    void doubleChargeIsEscalatedToTheTeamLead() {
        ProcessInstance instance = receive("Order 1042", "You charged twice for my order! I want my money back today.");

        Task task = awaitTask(instance);
        assertThat(task.getTaskDefinitionKey()).isEqualTo("AnswerEscalatedEmail");
        assertThat(taskService.getIdentityLinksForTask(task.getId()))
                .anySatisfy(link -> assertThat(link.getGroupId()).isEqualTo("leads"));
        @SuppressWarnings("unchecked")
        Map<String, Object> classification =
                (Map<String, Object>) runtimeService.getVariable(instance.getId(), "classification");
        assertThat(classification)
                .containsEntry("category", "billing")
                .containsEntry("urgency", "high")
                .containsEntry("sentiment", "negative");
        assertThat(runtimeService.getVariable(instance.getId(), "classificationTokens"))
                .isEqualTo(210L);

        taskService.claim(task.getId(), "bob");
        taskService.complete(task.getId());
        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .singleResult()
                        .getEndActivityId())
                .isEqualTo("EscalatedEmailAnswered");
    }

    @Test
    void invoiceQuestionGoesToBilling() {
        ProcessInstance instance = receive("Invoice", "Could you send me the invoice for my last order again?");

        Task task = awaitTask(instance);
        assertThat(task.getTaskDefinitionKey()).isEqualTo("AnswerBillingQuestion");
        JsonNode request = MODEL.requests().stream()
                .filter(r -> r.get("messages").get(1).get("content").asText().contains("invoice for my last order"))
                .findFirst()
                .orElseThrow();
        assertThat(request.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(request.get("messages").get(0).get("content").asText()).contains("\"sentiment\"");
    }

    @Test
    void proseAnswerIsRetriedAndThenRouted() {
        ProcessInstance instance = receive("Idea", "Please add a dark mode to the app, I love your shop!");

        Task task = awaitTask(instance);
        assertThat(task.getTaskDefinitionKey()).isEqualTo("AnswerSupportEmail");
        long calls = MODEL.requests().stream()
                .filter(r -> r.get("messages").get(1).get("content").asText().contains("dark mode"))
                .count();
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void answerThatNeverValidatesCreatesAnIncident() {
        ProcessInstance instance = receive("Hello", "Hello, is anybody there?");

        Incident incident = await().atMost(Duration.ofSeconds(60))
                .until(
                        () -> runtimeService
                                .createIncidentQuery()
                                .processInstanceId(instance.getId())
                                .singleResult(),
                        found -> found != null);
        assertThat(incident.getActivityId()).isEqualTo("ClassifyEmail");
        assertThat(incident.getIncidentMessage()).contains("did not validate against the JSON schema");
        assertThat(taskService
                        .createTaskQuery()
                        .processInstanceId(instance.getId())
                        .count())
                .isZero();
    }
}

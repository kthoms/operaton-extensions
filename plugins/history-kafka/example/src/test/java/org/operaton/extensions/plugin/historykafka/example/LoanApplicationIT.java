/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the loan process end to end: history reaches Kafka, the consumer builds the analytics view. */
@SpringBootTest
@Testcontainers
class LoanApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("operaton.extensions.plugin.history-kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @Autowired
    LoanAnalytics analytics;

    private final ObjectMapper json = new ObjectMapper();

    private ProcessInstance apply(int amount, int creditScore) {
        return runtimeService.startProcessInstanceByKey(
                "loan-application", Map.of("applicant", "alice", "amount", amount, "creditScore", creditScore));
    }

    private void awaitCompleted(long expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () -> assertThat(analytics.snapshot().completed()).isEqualTo(expected));
    }

    @Test
    void smallLowRiskLoanIsApprovedAutomaticallyAndItsHistoryIsOnKafka() throws Exception {
        LoanAnalytics.Snapshot before = analytics.snapshot();

        ProcessInstance instance = apply(5_000, 780);

        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .finished()
                        .count())
                .isEqualTo(1);
        awaitCompleted(before.completed() + 1);
        LoanAnalytics.Snapshot after = analytics.snapshot();
        assertThat(after.outcomes().getOrDefault("approved", 0L))
                .isEqualTo(before.outcomes().getOrDefault("approved", 0L) + 1);
        assertThat(after.started()).isEqualTo(before.started() + 1);

        List<JsonNode> events = eventsOf(instance.getId());
        assertThat(events)
                .extracting(e -> e.get("type").asText())
                .startsWith("process-instance.start")
                .endsWith("process-instance.end");
        assertThat(events)
                .filteredOn(e -> e.get("type").asText().equals("activity-instance.end"))
                .extracting(e -> e.get("data").get("activityId").asText())
                .containsSubsequence("application-received", "check-credit", "assess-risk", "approved");
    }

    @Test
    void borderlineLoanWaitsForTheUnderwriterAndIsRejectedByThem() {
        LoanAnalytics.Snapshot before = analytics.snapshot();

        ProcessInstance instance = apply(50_000, 650);
        awaitStarted(before.started() + 1);
        assertThat(analytics.snapshot().inFlight()).isEqualTo(before.inFlight() + 1);

        String taskId = taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getId();
        taskService.complete(taskId, Map.of("approved", false));

        awaitCompleted(before.completed() + 1);
        LoanAnalytics.Snapshot after = analytics.snapshot();
        assertThat(after.outcomes().getOrDefault("rejected", 0L))
                .isEqualTo(before.outcomes().getOrDefault("rejected", 0L) + 1);
        assertThat(after.inFlight()).isEqualTo(before.inFlight());
        assertThat(after.averageDurationMillisByActivity()).containsKey("review-application");
    }

    @Test
    void highRiskLoanIsRejectedImmediately() {
        LoanAnalytics.Snapshot before = analytics.snapshot();

        apply(1_000, 400);

        awaitCompleted(before.completed() + 1);
        assertThat(analytics.snapshot().outcomes().getOrDefault("rejected", 0L))
                .isEqualTo(before.outcomes().getOrDefault("rejected", 0L) + 1);
    }

    @Test
    void failedApplicationIsRolledBackAndNeverReachesKafka() {
        LoanAnalytics.Snapshot before = analytics.snapshot();
        long instancesBefore =
                historyService.createHistoricProcessInstanceQuery().count();

        // no credit score: the delegate throws, the whole start is rolled back
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey("loan-application", Map.of("amount", 100)))
                .hasMessageContaining("creditScore");
        assertThat(historyService.createHistoricProcessInstanceQuery().count()).isEqualTo(instancesBefore);

        // a later, good application: the topic is ordered, so once it is seen a rolled-back start would be too
        apply(2_000, 800);
        awaitCompleted(before.completed() + 1);
        assertThat(analytics.snapshot().started()).isEqualTo(before.started() + 1);
    }

    private void awaitStarted(long expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(analytics.snapshot().started()).isEqualTo(expected));
    }

    /** Reads the whole history topic with an independent consumer and returns the events of one instance. */
    private List<JsonNode> eventsOf(String processInstanceId) {
        List<JsonNode> events = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "it-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of("loan-history"));
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (processInstanceId.equals(record.key())) {
                        events.add(json.readTree(record.value()));
                    }
                }
                return events.stream().anyMatch(e -> e.get("type").asText().equals("process-instance.end"));
            });
        }
        return events;
    }
}

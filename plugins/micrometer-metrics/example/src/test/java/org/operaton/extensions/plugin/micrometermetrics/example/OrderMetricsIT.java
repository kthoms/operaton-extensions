/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs orders through the process (happy path with and without approval, error path with an unknown
 * article) and asserts that the plugin's metrics show up on {@code /actuator/prometheus}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "example.simulator.enabled=false",
            // @SpringBootTest switches metrics export off by default
            "management.prometheus.metrics.export.enabled=true",
            "operaton.extensions.plugin.micrometer-metrics.poll-interval=1s",
            "operaton.bpm.metrics.db-reporter-activate=true"
        })
@Testcontainers
class OrderMetricsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    ManagementService managementService;

    @Autowired
    HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void metricsAppearAfterRunningOrders() throws Exception {
        // happy path: two small orders ship directly
        start("small-1", 200, "SKU-1");
        start("small-2", 300, "SKU-2");
        // happy path with approval: a large order waits for the manager
        ProcessInstance large = start("large-1", 1500, "SKU-3");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(taskService
                                .createTaskQuery()
                                .processInstanceId(large.getId())
                                .count())
                        .isEqualTo(1));
        taskService.complete(taskService
                .createTaskQuery()
                .processInstanceId(large.getId())
                .singleResult()
                .getId());
        // error path: an unknown article fails the job, there are no retries, an incident opens
        ProcessInstance broken = start("broken-1", 200, ValidateOrderDelegate.UNKNOWN_SKU);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = scrape();
            assertThat(metric(
                            body,
                            "operaton_process_instances_started_total",
                            "process_definition_key=\"order-fulfillment\""))
                    .hasValue(4);
            assertThat(metric(body, "operaton_process_instances_ended_total", "state=\"completed\""))
                    .hasValue(3);
            assertThat(metric(body, "operaton_incidents_raised_total", "incident_type=\"failedJob\""))
                    .hasValue(1);
            assertThat(metric(body, "operaton_incidents_open", "incident_type=\"failedJob\""))
                    .hasValue(1);
            assertThat(metric(body, "operaton_jobs_failed")).hasValue(1);
            assertThat(metric(
                            body, "operaton_process_instances_running", "process_definition_key=\"order-fulfillment\""))
                    .hasValue(1);
            assertThat(metric(body, "operaton_engine_activity_instance_start_total")
                            .orElse(0))
                    .isGreaterThan(0);
        });

        // cancelling the broken order removes the incident again
        runtimeService.deleteProcessInstance(broken.getId(), "not in the catalog");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = scrape();
            assertThat(metric(body, "operaton_incidents_open", "incident_type=\"failedJob\""))
                    .hasValue(0);
            assertThat(metric(body, "operaton_incidents_deleted_total", "incident_type=\"failedJob\""))
                    .hasValue(1);
            assertThat(metric(
                            body, "operaton_process_instances_running", "process_definition_key=\"order-fulfillment\""))
                    .hasValue(0);
        });
        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .finished()
                        .count())
                .isGreaterThanOrEqualTo(4);
    }

    private ProcessInstance start(String orderId, long amount, String sku) {
        return runtimeService.startProcessInstanceByKey(
                "order-fulfillment", orderId, Map.of("amount", amount, "sku", sku));
    }

    private String scrape() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    /** Value of the first sample of the given metric whose label set contains all the given labels. */
    private static OptionalDouble metric(String body, String name, String... labels) {
        Pattern sample = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*})? (\\S+)$");
        return Stream.of(body.split("\n"))
                .map(sample::matcher)
                .filter(m -> m.matches()
                        && Stream.of(labels)
                                .allMatch(l -> m.group(1) != null && m.group(1).contains(l)))
                .mapToDouble(m -> Double.parseDouble(m.group(2)))
                .findFirst();
    }
}

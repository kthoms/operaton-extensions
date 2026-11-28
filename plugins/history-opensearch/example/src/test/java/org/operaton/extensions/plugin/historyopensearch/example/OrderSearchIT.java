/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs orders end to end (PostgreSQL engine, real OpenSearch) and finds them again by searching the indices. */
@SpringBootTest
@Testcontainers
class OrderSearchIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> opensearch = new GenericContainer<>("opensearchproject/opensearch:2.19.1")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=30s")
                    .forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void opensearchProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "operaton.extensions.plugin.history-opensearch.uris[0]",
                () -> "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200));
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @Autowired
    OrderSearch search;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private ProcessInstance order(String customer, String sku, long amount) {
        return runtimeService.startProcessInstanceByKey(
                "order-fulfilment", Map.of("customer", customer, "sku", sku, "amount", amount));
    }

    private Task taskOf(ProcessInstance instance) {
        return taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
    }

    private OrderSearch.OrderView awaitOrder(String customer, String state) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(search.findByCustomer(customer))
                        .singleElement()
                        .extracting(OrderSearch.OrderView::state)
                        .isEqualTo(state));
        return search.findByCustomer(customer).get(0);
    }

    @Test
    void anOrderThatIsShippedIsSearchableWithItsLifecycle() throws Exception {
        ProcessInstance instance = order("acme-1", "BOOK-42", 120);
        OrderSearch.OrderView active = awaitOrder("acme-1", "ACTIVE");
        assertThat(active.processInstanceId()).isEqualTo(instance.getId());
        assertThat(active.amount()).isEqualTo(120L);
        assertThat(active.endTime()).isNull();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(search.openTasks())
                        .extracting(OrderSearch.OpenTask::name, OrderSearch.OpenTask::processInstanceId)
                        .contains(org.assertj.core.groups.Tuple.tuple("Pack order", instance.getId())));

        taskService.complete(taskOf(instance).getId());

        OrderSearch.OrderView done = awaitOrder("acme-1", "COMPLETED");
        assertThat(done.processInstanceId()).isEqualTo(instance.getId());
        assertThat(done.startTime()).isEqualTo(active.startTime());
        assertThat(done.endTime()).isNotNull();
        assertThat(done.durationInMillis()).isGreaterThanOrEqualTo(0);
        assertThat(search.openTasks())
                .extracting(OrderSearch.OpenTask::processInstanceId)
                .doesNotContain(instance.getId());
        // one document per entity, merged from its start and end events
        JsonNode activities = rawSearch(
                "activity-instances",
                "{\"size\":50,\"query\":{\"term\":{\"processInstanceId\":\"" + instance.getId() + "\"}}}");
        assertThat(activities.path("hits").path("hits").findValues("activityId"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "order-received", "check-stock", "in-stock", "pack-order", "ship-order", "order-shipped");
        // the tracking number set by the process is a searchable variable
        JsonNode tracking = rawSearch(
                "variables",
                "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"processInstanceId\":\"" + instance.getId()
                        + "\"}},{\"term\":{\"variableName\":\"trackingNumber\"}}]}}}");
        assertThat(tracking.path("hits")
                        .path("hits")
                        .get(0)
                        .path("_source")
                        .path("valueText")
                        .asText())
                .isEqualTo("TRK-" + instance.getId());
    }

    @Test
    void anOutOfStockOrderWaitsForTheBackorderAndTheSummaryCountsIt() {
        ProcessInstance instance = order("globex-1", "OUT-77", 9_900);
        awaitOrder("globex-1", "ACTIVE");
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(search.openTasks())
                        .extracting(OrderSearch.OpenTask::name)
                        .contains("Arrange backorder"));
        assertThat(search.summary().byState()).containsKey("ACTIVE");

        taskService.complete(taskOf(instance).getId());

        OrderSearch.OrderView done = awaitOrder("globex-1", "COMPLETED");
        assertThat(done.amount()).isEqualTo(9_900L);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            OrderSearch.Summary summary = search.summary();
            assertThat(summary.byState()).containsKey("COMPLETED");
            assertThat(summary.total()).isGreaterThanOrEqualTo(1);
            assertThat(summary.averageDurationMillis()).isNotNull().isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void anOrderThatFailsIsRolledBackAndNeverIndexed() {
        long instancesBefore =
                historyService.createHistoricProcessInstanceQuery().count();

        // no sku: the stock check throws and the whole start is rolled back
        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(
                        "order-fulfilment", Map.of("customer", "ghost-customer", "amount", 1)))
                .hasMessageContaining("sku");
        assertThat(historyService.createHistoricProcessInstanceQuery().count()).isEqualTo(instancesBefore);

        // the writer is a single queue: once a later order is searchable, a rolled-back one would be too
        ProcessInstance later = order("initech-1", "PEN-1", 5);
        awaitOrder("initech-1", "ACTIVE");
        assertThat(search.findByCustomer("ghost-customer")).isEmpty();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(search.openTasks())
                        .extracting(OrderSearch.OpenTask::processInstanceId)
                        .contains(later.getId()));
    }

    private JsonNode rawSearch(String kind, String body) throws Exception {
        String url = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200) + "/operaton-" + kind
                + "-*/_search?ignore_unavailable=true&allow_no_indices=true";
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}

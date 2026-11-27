/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
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

/** Runs the order process end to end and asserts the span hierarchy that arrived in Jaeger. */
@SpringBootTest
@Testcontainers
class OrderFulfilmentIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> jaeger = new GenericContainer<>("jaegertracing/all-in-one:1.76.0")
            .withEnv("COLLECTOR_OTLP_ENABLED", "true")
            .withExposedPorts(4318, 16686)
            .waitingFor(Wait.forHttp("/").forPort(16686));

    @DynamicPropertySource
    static void otel(DynamicPropertyRegistry registry) {
        registry.add("example.otel.endpoint", () -> "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(4318));
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    /** A span of a Jaeger trace. */
    record JaegerSpan(String id, String parentId, String traceId, String operation) {}

    private List<JaegerSpan> spansOf(String businessKey) throws Exception {
        String tags = URLEncoder.encode(
                "{\"operaton.process.business_key\":\"" + businessKey + "\"}", StandardCharsets.UTF_8);
        URI uri = URI.create("http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(16686)
                + "/api/traces?service=order-fulfilment&limit=5&tags=" + tags);
        HttpResponse<String> response =
                http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        List<Map<String, Object>> traces = JsonPath.read(response.body(), "$.data");
        if (traces.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> spans = JsonPath.read(response.body(), "$.data[0].spans");
        return spans.stream()
                .map(s -> {
                    List<Map<String, Object>> refs = (List<Map<String, Object>>) s.get("references");
                    String parent = refs.isEmpty() ? null : (String) refs.get(0).get("spanID");
                    return new JaegerSpan((String) s.get("spanID"), parent, (String) s.get("traceID"), (String)
                            s.get("operationName"));
                })
                .toList();
    }

    private List<JaegerSpan> awaitSpans(String businessKey, String lastOperation) {
        List<JaegerSpan>[] holder = new List[1];
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    holder[0] = spansOf(businessKey);
                    assertThat(holder[0])
                            .extracting(JaegerSpan::operation)
                            .contains("process order-fulfilment", lastOperation);
                });
        return holder[0];
    }

    private static JaegerSpan span(List<JaegerSpan> spans, String operation) {
        List<JaegerSpan> found =
                spans.stream().filter(s -> s.operation().equals(operation)).toList();
        assertThat(found).as("span '%s' in %s", operation, spans).hasSize(1);
        return found.get(0);
    }

    private void awaitFinished(String processInstanceId) {
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(historyService
                                .createHistoricProcessInstanceQuery()
                                .processInstanceId(processInstanceId)
                                .finished()
                                .count())
                        .isEqualTo(1));
    }

    @Test
    void highValueOrderIsTracedAcrossApprovalJobAndExternalTask() throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("orderTotal", 1500L);
        variables.put("card", "ok");
        String id = runtimeService
                .startProcessInstanceByKey("order-fulfilment", "ORD-HAPPY", variables)
                .getId();
        Task approval = await().atMost(Duration.ofSeconds(30))
                .until(() -> taskService.createTaskQuery().processInstanceId(id).singleResult(), t -> t != null);
        taskService.complete(approval.getId());
        awaitFinished(id);

        List<JaegerSpan> spans = awaitSpans("ORD-HAPPY", "noneEndEvent orderFulfilled");

        JaegerSpan process = span(spans, "process order-fulfilment");
        assertThat(process.parentId()).isNull();
        assertThat(spans).extracting(JaegerSpan::traceId).containsOnly(process.traceId());
        assertThat(span(spans, "serviceTask validateOrder").parentId()).isEqualTo(process.id());
        assertThat(span(spans, "userTask approveOrder").parentId()).isEqualTo(process.id());
        JaegerSpan job = span(spans, "job async-continuation");
        assertThat(job.parentId()).isEqualTo(process.id());
        assertThat(span(spans, "serviceTask chargePayment").parentId()).isEqualTo(job.id());
        JaegerSpan ship = span(spans, "serviceTask shipOrder");
        assertThat(span(spans, "external-task fetch ship-order").parentId()).isEqualTo(ship.id());
        assertThat(span(spans, "external-task complete ship-order").parentId()).isEqualTo(ship.id());
        assertThat(span(spans, "noneEndEvent orderFulfilled").parentId()).isEqualTo(process.id());
    }

    @Test
    void declinedPaymentEndsInTheCancelledBranchOfTheSameTrace() throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("orderTotal", 50L);
        variables.put("card", "declined");
        String id = runtimeService
                .startProcessInstanceByKey("order-fulfilment", "ORD-DECLINED", variables)
                .getId();
        awaitFinished(id);

        List<JaegerSpan> spans = awaitSpans("ORD-DECLINED", "noneEndEvent orderCancelled");

        JaegerSpan process = span(spans, "process order-fulfilment");
        assertThat(spans).extracting(JaegerSpan::traceId).containsOnly(process.traceId());
        assertThat(span(spans, "job async-continuation").parentId()).isEqualTo(process.id());
        assertThat(spans).extracting(JaegerSpan::operation).doesNotContain("noneEndEvent orderFulfilled");
        assertThat(spans).extracting(JaegerSpan::operation).noneMatch(o -> o.contains("ship-order"));
    }
}

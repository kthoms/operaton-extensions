/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
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

/**
 * Runs the payment process end to end against PostgreSQL and a real SMTP server (Mailpit): a declined
 * payment creates an incident and the alert arrives in the inbox and at a webhook.
 */
@SpringBootTest
@Testcontainers
class PaymentIncidentNotificationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static final List<String> WEBHOOK_BODIES = new CopyOnWriteArrayList<>();
    static final HttpServer WEBHOOK = startWebhook();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("operaton.extensions.plugin.incident-notifier.email.port", () -> mailpit.getMappedPort(1025));
        registry.add("operaton.extensions.plugin.incident-notifier.email.host", mailpit::getHost);
        registry.add("operaton.extensions.plugin.incident-notifier.throttle", () -> "PT0S");
        registry.add(
                "operaton.extensions.plugin.incident-notifier.webhook.url",
                () -> "http://127.0.0.1:" + WEBHOOK.getAddress().getPort() + "/alerts");
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    HistoryService historyService;

    private final HttpClient http = HttpClient.newHttpClient();

    private static HttpServer startWebhook() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/alerts", exchange -> {
                WEBHOOK_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("cannot start webhook receiver", e);
        }
    }

    @AfterAll
    static void stopWebhook() {
        WEBHOOK.stop(0);
    }

    @BeforeEach
    void clearInbox() throws Exception {
        WEBHOOK_BODIES.clear();
        mailpitCall("DELETE", "/api/v1/messages");
    }

    @Test
    void smallPaymentCompletesWithoutAlert() throws Exception {
        ProcessInstance instance =
                runtimeService.startProcessInstanceByKey("payment-processing", "order-1", Map.of("amount", 250));

        await().untilAsserted(() -> assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .finished()
                        .count())
                .isEqualTo(1));
        assertThat(runtimeService
                        .createIncidentQuery()
                        .processInstanceId(instance.getId())
                        .count())
                .isZero();
        assertThat(mailpitCall("GET", "/api/v1/messages")).contains("\"total\":0");
        assertThat(WEBHOOK_BODIES).isEmpty();
    }

    @Test
    void declinedPaymentCreatesAnIncidentAndAlertsByMailAndWebhook() throws Exception {
        ProcessInstance instance =
                runtimeService.startProcessInstanceByKey("payment-processing", "order-2", Map.of("amount", 5000));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(runtimeService
                                .createIncidentQuery()
                                .processInstanceId(instance.getId())
                                .count())
                        .isEqualTo(1));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(mailpitCall("GET", "/api/v1/messages"))
                        .contains("[Operaton] 1 incident(s) in process payment-processing"));
        String mail = mailpitCall("GET", "/api/v1/message/latest");
        assertThat(mail)
                .contains("ops@example.org")
                .contains(instance.getId())
                .contains("order-2")
                .contains("Payment gateway declined 5000 EUR")
                .contains("http://localhost:8080/app/cockpit/default/#/process-instance/" + instance.getId());

        await().untilAsserted(() -> assertThat(WEBHOOK_BODIES).hasSize(1));
        assertThat(WEBHOOK_BODIES.get(0))
                .contains("\"processDefinitionKey\":\"payment-processing\"")
                .contains("\"businessKey\":\"order-2\"")
                .contains("\"activityId\":\"chargePayment\"")
                .contains(instance.getId());
    }

    private String mailpitCall(String method, String path) throws Exception {
        URI uri = URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + path);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return response.body();
    }
}

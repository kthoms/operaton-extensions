/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
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

/**
 * The round trip, end to end: a customer mail reaches the support mailbox (GreenMail), the poller starts the ticket
 * process, an agent answers, and the reply arrives in the outgoing mail server (Mailpit).
 */
@SpringBootTest(properties = "operaton.extensions.connector.mail.inbound.poll-interval=1s")
@Testcontainers
class SupportTicketIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> greenmail = new GenericContainer<>("greenmail/standalone:2.1.8")
            .withEnv(
                    "GREENMAIL_OPTS",
                    "-Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.hostname=0.0.0.0 "
                            + "-Dgreenmail.users=support:secret@localhost -Dgreenmail.auth.disabled")
            .withExposedPorts(3025, 3143, 8080)
            .waitingFor(Wait.forHttp("/api/service/readiness").forPort(8080));

    @Container
    static GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.21")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @DynamicPropertySource
    static void mailServers(DynamicPropertyRegistry registry) {
        registry.add("operaton.extensions.connector.mail.smtp.host", mailpit::getHost);
        registry.add("operaton.extensions.connector.mail.smtp.port", () -> mailpit.getMappedPort(1025));
        registry.add("operaton.extensions.connector.mail.imap.host", greenmail::getHost);
        registry.add("operaton.extensions.connector.mail.imap.port", () -> greenmail.getMappedPort(3143));
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    private static void customerSends(String from, String subject, String text) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", greenmail.getHost());
        properties.setProperty("mail.smtp.port", String.valueOf(greenmail.getMappedPort(3025)));
        MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setFrom(new InternetAddress(from));
        message.setRecipients(RecipientType.TO, "support@localhost");
        message.setSubject(subject);
        message.setText(text);
        Transport.send(message);
    }

    private static String mailpitMessages() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://" + mailpit.getHost() + ":"
                                        + mailpit.getMappedPort(8025) + "/api/v1/messages"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    @Test
    void aSupportMailIsAnsweredByMail() throws Exception {
        customerSends("carol@customer.example", "[T-100] Problem with my invoice", "The total is wrong.");

        Task task = await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> taskService
                                .createTaskQuery()
                                .taskName("Answer ticket")
                                .singleResult(),
                        t -> t != null);
        ProcessInstance instance = runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        assertThat(instance.getBusinessKey()).isEqualTo("T-100");
        assertThat(runtimeService.getVariable(instance.getId(), "mailFromAddress"))
                .isEqualTo("carol@customer.example");

        taskService.claim(task.getId(), "alice");
        taskService.complete(task.getId(), Map.of("answer", "We corrected the total."));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String messages = mailpitMessages();
            assertThat(messages).contains("Re: [T-100] Problem with my invoice").contains("carol@customer.example");
        });
        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .finished()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aMailThatMatchesNoRouteStartsNothing() throws Exception {
        long before = runtimeService.createProcessInstanceQuery().count();
        customerSends("spam@elsewhere.example", "Buy now", "cheap offer");
        customerSends("carol@customer.example", "Help: second request", "please");

        // the second mail proves the poller ran after the first one arrived
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(
                                runtimeService.createProcessInstanceQuery().count())
                        .isEqualTo(before + 1));
        assertThat(runtimeService
                        .createProcessInstanceQuery()
                        .variableValueEquals("mailSubject", "Buy now")
                        .count())
                .isZero();
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.extensions.connector.mail.MailConnectorPlugin;
import org.operaton.extensions.connector.mail.inbound.MailInboundPoller;
import org.operaton.extensions.connector.mail.service.MailService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

class MailStarterTest {

    @SpringBootApplication
    static class App {}

    private GreenMail greenMail;

    @BeforeEach
    void start() {
        greenMail = new GreenMail(ServerSetupTest.SMTP_IMAP);
        greenMail.start();
        greenMail.setUser("support@localhost", "secret");
    }

    @AfterEach
    void stop() {
        greenMail.stop();
    }

    private ConfigurableApplicationContext run(String... extra) {
        String[] base = {
            "--spring.datasource.url=jdbc:h2:mem:starter-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
            "--spring.main.web-application-type=none",
            "--operaton.bpm.generate-unique-process-engine-name=true",
            "--operaton.bpm.generate-unique-process-application-name=true",
            "--operaton.extensions.connector.mail.smtp.host=localhost",
            "--operaton.extensions.connector.mail.smtp.port=3025",
            "--operaton.extensions.connector.mail.imap.host=localhost",
            "--operaton.extensions.connector.mail.imap.port=3143",
            "--operaton.extensions.connector.mail.imap.username=support@localhost",
            "--operaton.extensions.connector.mail.imap.password=secret",
            "--operaton.extensions.connector.mail.sender=bpm@localhost"
        };
        String[] all = java.util.stream.Stream.concat(java.util.Arrays.stream(base), java.util.Arrays.stream(extra))
                .toArray(String[]::new);
        return SpringApplication.run(App.class, all);
    }

    @Test
    void registersThePluginAndSettingsFromProperties() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBean(MailConnectorPlugin.class)
                            .getMailService()
                            .settings()
                            .sender())
                    .isEqualTo("bpm@localhost");
            assertThat(context.getBeansOfType(MailInboundPoller.class)).isEmpty();
            assertThat(context.getBean(MailService.class).settings().toString()).doesNotContain("secret");
        }
    }

    @Test
    void invalidConfigurationStopsTheApplication() {
        assertThatThrownBy(() -> SpringApplication.run(
                        App.class,
                        "--spring.datasource.url=jdbc:h2:mem:bad;DB_CLOSE_DELAY=-1",
                        "--spring.main.web-application-type=none"))
                .hasStackTraceContaining("no host configured");
    }

    @Test
    void inboundPollerStartsProcessesFromRoutes() throws Exception {
        try (ConfigurableApplicationContext context = run(
                "--operaton.extensions.connector.mail.inbound.enabled=true",
                "--operaton.extensions.connector.mail.inbound.poll-interval=200ms",
                "--operaton.extensions.connector.mail.inbound.routes[0].name=any",
                "--operaton.extensions.connector.mail.inbound.routes[0].process-definition-key=unknown-process")) {
            assertThat(context.getBean(MailInboundPoller.class).isRunning()).isTrue();
            // a route to a process that does not exist: the mail is flagged and counted as failed, loudly
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress("a@example.org"));
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, "support@localhost");
            message.setSubject("hello");
            message.setText("x");
            message.saveChanges();
            greenMail.getUserManager().getUserByEmail("support@localhost").deliver(message);

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(
                                    context.getBean(MailInboundPoller.class).failedCount())
                            .isEqualTo(1));
            assertThat(context.getBean(RuntimeService.class)
                            .createProcessInstanceQuery()
                            .count())
                    .isZero();
            MailInboundPoller poller = context.getBean(MailInboundPoller.class);
            context.close();
            assertThat(poller.isRunning()).isFalse();
        }
    }
}

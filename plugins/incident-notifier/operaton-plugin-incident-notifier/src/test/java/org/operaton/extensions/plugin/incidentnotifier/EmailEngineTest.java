/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** Mail notifications of real incidents received by an in-JVM SMTP server (GreenMail). */
class EmailEngineTest {

    static final GreenMail MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    static final IncidentNotifierPlugin PLUGIN = new IncidentNotifierPlugin();

    static {
        MAIL.start();
        PLUGIN.setThrottleMillis(0);
        PLUGIN.setCockpitBaseUrl("http://cockpit.test");
        PLUGIN.setMailHost("127.0.0.1");
        PLUGIN.setMailPort(MAIL.getSmtp().getPort());
        PLUGIN.setMailFrom("operaton@example.org");
        PLUGIN.setMailTo("ops@example.org");
    }

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .configurator(cfg -> cfg.getProcessEnginePlugins().add(PLUGIN))
            .closeEngineAfterAllTests()
            .build();

    ProcessEngine processEngine;

    @BeforeEach
    void setUp() throws Exception {
        processEngine = engine.getProcessEngine();
        MAIL.purgeEmailFromAllMailboxes();
    }

    @AfterEach
    void tearDown() {
        EngineFixtures.cleanUp(processEngine);
    }

    @AfterAll
    static void stop() {
        PLUGIN.close();
        MAIL.stop();
    }

    @Test
    void mailsTheDefaultRecipient() throws Exception {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-mail");

        String instanceId = EngineFixtures.startAndFail(processEngine, "pay-mail", "bk-9", Map.of());

        await().untilAsserted(() -> assertThat(MAIL.getReceivedMessages()).hasSize(1));
        MimeMessage mail = MAIL.getReceivedMessages()[0];
        assertThat(mail.getSubject()).isEqualTo("[Operaton] 1 incident(s) in process pay-mail");
        assertThat(mail.getFrom()[0].toString()).isEqualTo("operaton@example.org");
        assertThat(addresses(mail, Message.RecipientType.TO)).containsExactly("ops@example.org");
        String body = (String) mail.getContent();
        assertThat(body)
                .contains("Incident type: failedJob")
                .contains("payment gateway unavailable")
                .contains("Activity: charge")
                .contains("Business key: bk-9")
                .contains("http://cockpit.test/app/cockpit/default/#/process-instance/" + instanceId);
    }

    @Test
    void processVariablesOverrideTheRecipients() throws Exception {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-mail-var");

        EngineFixtures.startAndFail(
                processEngine,
                "pay-mail-var",
                null,
                Map.of(
                        "incidentNotificationReceiver",
                        "owner@example.org",
                        "incidentNotificationCc",
                        "lead@example.org"));

        await().untilAsserted(() ->
                assertThat(MAIL.getReceivedMessages()).hasSize(2)); // one stored copy per mailbox: to and cc
        MimeMessage mail = MAIL.getReceivedMessages()[0];
        assertThat(addresses(mail, Message.RecipientType.TO)).containsExactly("owner@example.org");
        assertThat(addresses(mail, Message.RecipientType.CC)).containsExactly("lead@example.org");
    }

    private static List<String> addresses(MimeMessage mail, Message.RecipientType type) throws Exception {
        return Arrays.stream(mail.getRecipients(type)).map(Object::toString).toList();
    }
}

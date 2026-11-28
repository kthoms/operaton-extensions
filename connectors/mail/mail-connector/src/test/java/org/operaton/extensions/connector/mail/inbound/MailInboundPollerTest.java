/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.store.MailFolder;
import jakarta.mail.Flags.Flag;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailServerSupport;
import org.operaton.extensions.connector.mail.config.MailSettings;
import org.operaton.extensions.connector.mail.service.MailService;

@Deployment(resources = "processes/support-ticket.bpmn")
class MailInboundPollerTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private MailServerSupport server;
    private MailService mailService;
    private RuntimeService runtime;

    @BeforeEach
    void setUp() {
        server = new MailServerSupport();
        mailService = new MailService(MailSettings.load("classpath:mail-config.properties"));
        runtime = engine.getProcessEngine().getRuntimeService();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private MailInboundPoller poller(InboundSettings settings) {
        return new MailInboundPoller(mailService, settings, runtime);
    }

    private static InboundSettings.Builder settings() {
        return InboundSettings.builder().pollInterval(Duration.ofMillis(200));
    }

    private MailFolder inbox() throws Exception {
        return server.greenMail()
                .getManagers()
                .getImapHostManager()
                .getInbox(server.greenMail().getUserManager().getUserByEmail(MailServerSupport.USER));
    }

    @Test
    void startsAProcessFromAMailWithVariablesAndAttachments() throws Exception {
        server.deliverWithAttachment(
                "Alice <alice@example.org>",
                "[TICKET-1] Printer broken",
                "It does not print",
                "photo.txt",
                "text/plain",
                "evidence".getBytes(StandardCharsets.UTF_8));
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("tickets")
                        .subjectPattern("^\\[TICKET-\\d+]")
                        .businessKeyPattern("\\[(TICKET-\\d+)]")
                        .processDefinitionKey("ticket")
                        .build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        ProcessInstance instance = runtime.createProcessInstanceQuery()
                .processDefinitionKey("ticket")
                .singleResult();
        assertThat(instance.getBusinessKey()).isEqualTo("TICKET-1");
        assertThat(runtime.getVariable(instance.getId(), "mailFromAddress")).isEqualTo("alice@example.org");
        assertThat(runtime.getVariable(instance.getId(), "mailSubject")).isEqualTo("[TICKET-1] Printer broken");
        assertThat((String) runtime.getVariable(instance.getId(), "mailText")).contains("It does not print");
        assertThat((String) runtime.getVariable(instance.getId(), "mailMessageId"))
                .isNotBlank();
        assertThat(runtime.getVariable(instance.getId(), "mailAttachmentCount")).isEqualTo(1);
        FileValue file = runtime.getVariableTyped(instance.getId(), "mailAttachment1");
        assertThat(file.getFilename()).isEqualTo("photo.txt");
        assertThat(file.getValue()).hasContent("evidence");
        // processed: marked read, not deleted
        assertThat(inbox().getMessageCount()).isEqualTo(1);
        assertThat(inbox().getMessages().get(0).isSet(Flag.SEEN)).isTrue();
        // and not processed twice
        assertThat(poller.pollOnce()).isZero();
        assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(1);
        assertThat(poller.processedCount()).isEqualTo(1);
    }

    @Test
    void aReplyWithTheBusinessKeyCorrelatesToTheRunningProcess() throws Exception {
        ProcessInstance ticket = runtime.startProcessInstanceByKey("ticket", "TICKET-7");
        server.deliver("alice@example.org", "Re: [TICKET-7] Printer", "still broken");
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("replies")
                        .businessKeyPattern("\\[(TICKET-\\d+)]")
                        .messageName("ticket-reply")
                        .processDefinitionKey("ticket")
                        .build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runtime.createProcessInstanceQuery()
                        .processInstanceId(ticket.getId())
                        .count())
                .isZero();
    }

    @Test
    void aMailWithoutKeyStartsAProcessThroughAMessageStartEvent() throws Exception {
        server.deliver("alice@example.org", "Help", "please");
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("new").messageName("new-request").build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("ticket-by-message")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void routesAreMatchedBySenderAndSubjectFirstMatchWins() throws Exception {
        server.deliver("vip@example.org", "Anything", "x");
        server.deliver("other@example.org", "Anything", "y");
        server.deliver("other@example.org", "Unrelated", "z");
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("vip")
                        .fromPattern("^vip@")
                        .processDefinitionKey("ticket")
                        .businessKeyPattern("(never)")
                        .build())
                .route(MailRoute.builder("anything")
                        .subjectPattern("Anything")
                        .messageName("new-request")
                        .build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(2);

        assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("ticket")
                        .count())
                .isEqualTo(1);
        assertThat(runtime.createProcessInstanceQuery()
                        .processDefinitionKey("ticket-by-message")
                        .count())
                .isEqualTo(1);
        // the mail without a matching route is left untouched (still unread)
        assertThat(inbox().getMessages().stream()
                        .filter(m -> !m.isSet(Flag.SEEN))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aFailingMailIsFlaggedLoggedAndDoesNotBlockTheOthers() throws Exception {
        server.deliver("alice@example.org", "[TICKET-404] unknown", "no such ticket");
        server.deliver("alice@example.org", "[TICKET-1] fine", "ok");
        runtime.startProcessInstanceByKey("ticket", "TICKET-1");
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("replies")
                        .businessKeyPattern("\\[(TICKET-\\d+)]")
                        .messageName("ticket-reply")
                        .build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(poller.failedCount()).isEqualTo(1);
        List<com.icegreen.greenmail.store.StoredMessage> messages = inbox().getMessages();
        assertThat(messages.get(0).isSet(Flag.FLAGGED)).isTrue();
        assertThat(messages.get(0).isSet(Flag.SEEN)).isFalse();
        assertThat(messages.get(1).isSet(Flag.SEEN)).isTrue();
        // the failed mail is not retried
        assertThat(poller.pollOnce()).isZero();
        assertThat(poller.failedCount()).isEqualTo(1);
    }

    @Test
    void completionDeleteRemovesTheMail() throws Exception {
        server.deliver("alice@example.org", "Help", "x");
        MailInboundPoller poller = poller(settings()
                .completion(InboundSettings.Completion.DELETE)
                .route(MailRoute.builder("new").processDefinitionKey("ticket").build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(inbox().getMessageCount()).isZero();
    }

    @Test
    void completionMoveFilesTheMailInTheProcessedFolder() throws Exception {
        server.deliver("alice@example.org", "Help", "x");
        MailInboundPoller poller = poller(settings()
                .completion(InboundSettings.Completion.MOVE)
                .processedFolder("Processed")
                .route(MailRoute.builder("new").processDefinitionKey("ticket").build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(inbox().getMessageCount()).isZero();
        var processed = server.greenMail()
                .getManagers()
                .getImapHostManager()
                .getFolder(server.greenMail().getUserManager().getUserByEmail(MailServerSupport.USER), "Processed");
        assertThat(processed.getMessageCount()).isEqualTo(1);
    }

    @Test
    void batchSizeLimitsTheMailsPerPoll() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.deliver("alice@example.org", "Mail " + i, "x");
        }
        MailInboundPoller poller = poller(settings()
                .batchSize(2)
                .route(MailRoute.builder("all").processDefinitionKey("ticket").build())
                .build());

        assertThat(poller.pollOnce()).isEqualTo(2);
        assertThat(poller.pollOnce()).isEqualTo(1);
    }

    @Test
    void pollsPeriodicallyUntilClosed() throws Exception {
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("all").processDefinitionKey("ticket").build())
                .build());
        poller.start();
        try {
            server.deliver("alice@example.org", "Late", "x");

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(runtime.createProcessInstanceQuery()
                                    .processDefinitionKey("ticket")
                                    .count())
                            .isEqualTo(1));
        } finally {
            poller.close();
        }
        assertThat(poller.isRunning()).isFalse();
    }

    @Test
    void theScheduleSurvivesAnOutageAndFailsLoudlyOnDirectPolls() throws Exception {
        MailInboundPoller poller = poller(settings()
                .route(MailRoute.builder("all").processDefinitionKey("ticket").build())
                .build());
        server.close();

        assertThatThrownBy(poller::pollOnce).isInstanceOf(MailConnectorException.class);
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> settings().build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("at least one route");
        assertThatThrownBy(() -> MailRoute.builder("x").build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("processDefinitionKey or messageName");
        assertThatThrownBy(() -> MailRoute.builder("x")
                        .messageName("m")
                        .subjectPattern("(")
                        .build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("subjectPattern");
        assertThatThrownBy(() -> MailRoute.builder("x")
                        .messageName("m")
                        .businessKeyPattern("no group")
                        .build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("capturing group");
        assertThatThrownBy(() -> settings()
                        .completion(InboundSettings.Completion.MOVE)
                        .route(MailRoute.builder("x").messageName("m").build())
                        .build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("processedFolder");
        assertThatThrownBy(() -> settings()
                        .route(MailRoute.builder("x").messageName("m").build())
                        .route(MailRoute.builder("x").messageName("n").build())
                        .build())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("duplicate");
    }
}

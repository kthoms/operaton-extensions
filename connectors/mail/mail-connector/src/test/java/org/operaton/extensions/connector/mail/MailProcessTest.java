/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;

/** The connectors used from BPMN through {@code operaton:connector}, against an in-JVM mail server. */
class MailProcessTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    private MailServerSupport server;

    @BeforeEach
    void startServer() {
        server = new MailServerSupport();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private RuntimeService runtime() {
        return engine.getProcessEngine().getRuntimeService();
    }

    @Test
    void pluginIsRegisteredAndConfiguredInTheEngine() {
        ProcessEngineConfigurationImpl configuration = engine.getProcessEngineConfiguration();

        assertThat(configuration.getProcessEnginePlugins())
                .anySatisfy(plugin -> assertThat(plugin)
                        .isInstanceOfSatisfying(
                                MailConnectorPlugin.class,
                                mail -> assertThat(
                                                mail.getMailService().settings().sender())
                                        .isEqualTo("bpm@localhost")));
    }

    @Test
    @Deployment(resources = "processes/mail-send-text.bpmn")
    void sendsMailFromAServiceTask() throws Exception {
        runtime()
                .startProcessInstanceByKey(
                        "send-text",
                        Variables.createVariables()
                                .putValue("recipient", "test@localhost")
                                .putValue("orderId", 4711));

        MimeMessage[] mails = server.greenMail().getReceivedMessages();
        assertThat(mails).hasSize(1);
        assertThat(mails[0].getSubject()).isEqualTo("Order 4711");
        assertThat(GreenMailUtil.getBody(mails[0])).isEqualTo("Your order was received.");
    }

    @Test
    @Deployment(resources = "processes/mail-send-text.bpmn")
    void aFailingConnectorFailsTheProcessLoudly() {
        assertThatThrownBy(() -> runtime()
                        .startProcessInstanceByKey(
                                "send-text",
                                Variables.createVariables()
                                        .putValue("recipient", null)
                                        .putValue("orderId", 1)))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("missing parameter 'to'");
        assertThat(server.greenMail().getReceivedMessages()).isEmpty();
    }

    @Test
    @Deployment(resources = "processes/mail-send-template.bpmn")
    void sendsTemplatedMailWithAFileVariableAsAttachment() throws Exception {
        runtime()
                .startProcessInstanceByKey(
                        "send-template",
                        Variables.createVariables()
                                .putValue("recipient", "test@localhost")
                                .putValue("customer", "Ada")
                                .putValue("number", "INV-7")
                                .putValueTyped(
                                        "invoice",
                                        Variables.fileValue("invoice-7.txt")
                                                .file("total: 42".getBytes(StandardCharsets.UTF_8))
                                                .mimeType("text/plain")
                                                .create()));

        MimeMessage mail = server.greenMail().getReceivedMessages()[0];
        assertThat(mail.getSubject()).isEqualTo("Invoice INV-7");
        MimeMultipart multipart = (MimeMultipart) mail.getContent();
        assertThat(multipart.getCount()).isEqualTo(2);
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(0)))
                .isEqualTo("Dear Ada, your invoice INV-7 is attached.");
        assertThat(multipart.getBodyPart(1).getFileName()).isEqualTo("invoice-7.txt");
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(1))).isEqualTo("total: 42");
    }

    @Test
    @Deployment(resources = "processes/mail-send-template.bpmn")
    void aMissingAttachmentVariableFailsLoudly() {
        assertThatThrownBy(() -> runtime()
                        .startProcessInstanceByKey(
                                "send-template",
                                Variables.createVariables()
                                        .putValue("recipient", "test@localhost")
                                        .putValue("customer", "Ada")
                                        .putValue("number", "INV-7")))
                .hasStackTraceContaining("'invoice'");
    }

    @Test
    @Deployment(resources = "processes/mail-send-freemarker.bpmn")
    void sendsMailRenderedWithFreemarker() {
        // ${...} in a model is evaluated by the engine, so the FreeMarker template travels as a variable
        runtime()
                .startProcessInstanceByKey(
                        "send-freemarker",
                        Variables.createVariables().putValue("template", "<#list items as i>${i}<#sep>, </#list>"));

        assertThat(GreenMailUtil.getBody(server.greenMail().getReceivedMessages()[0]))
                .isEqualTo("a, b");
    }

    @Test
    @Deployment(resources = "processes/mail-poll-delete.bpmn")
    void pollsMailsIntoVariablesAndDeletesThem() throws Exception {
        server.deliver("alice@example.org", "Invoice 1", "a");
        server.deliver("alice@example.org", "Chat", "b");
        server.deliver("bob@example.org", "Invoice 2", "c");

        var instance = runtime().startProcessInstanceByKey("poll-delete");

        // the process has finished: poll output was handed to the delete task
        assertThat(runtime()
                        .createProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .count())
                .isZero();
        var historic = engine.getProcessEngine()
                .getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("mailCount")
                .singleResult();
        assertThat(historic.getValue()).isEqualTo(2);
        MimeMessage[] remaining = server.greenMail().getReceivedMessages();
        assertThat(remaining).hasSize(1);
        assertThat(remaining[0].getSubject()).isEqualTo("Chat");
    }
}

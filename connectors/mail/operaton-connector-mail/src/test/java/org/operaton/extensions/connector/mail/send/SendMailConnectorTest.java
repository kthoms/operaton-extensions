/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.connect.Connectors;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailServerSupport;

class SendMailConnectorTest {

    private MailServerSupport server;
    private SendMailConnector connector;

    @BeforeEach
    void startServer() {
        server = new MailServerSupport();
        connector = Connectors.getConnector(SendMailConnector.CONNECTOR_ID);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private MimeMessage single() {
        MimeMessage[] received = server.greenMail().getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    @Test
    void isRegisteredThroughTheConnectorSpi() {
        assertThat(connector).isNotNull();
        assertThat((Object) Connectors.getConnector("mail-poll")).isNotNull();
        assertThat((Object) Connectors.getConnector("mail-delete")).isNotNull();
    }

    @Test
    void sendsPlainTextWithConfiguredDefaultSender() throws Exception {
        connector
                .createRequest()
                .to("test@localhost")
                .subject("Hello")
                .text("plain body")
                .execute();

        MimeMessage mail = single();
        assertThat(mail.getSubject()).isEqualTo("Hello");
        assertThat(mail.getFrom()[0].toString()).contains("BPM Robot").contains("bpm@localhost");
        assertThat(GreenMailUtil.getBody(mail)).isEqualTo("plain body");
    }

    @Test
    void sendsToCcAndBccAndReplyTo() throws Exception {
        connector
                .createRequest()
                .from("a@localhost")
                .to("test@localhost")
                .cc("cc1@localhost,cc2@localhost")
                .bcc("bcc@localhost")
                .replyTo("reply@localhost")
                .subject("Multi")
                .text("x")
                .execute();

        MimeMessage mail = server.greenMail().getReceivedMessages()[0];
        assertThat(mail.getRecipients(RecipientType.CC)).hasSize(2);
        assertThat(mail.getReplyTo()[0].toString()).isEqualTo("reply@localhost");
        // one copy per recipient, the Bcc one included
        assertThat(server.greenMail().getReceivedMessages()).hasSize(4);
    }

    @Test
    void acceptsRecipientListsAsCollections() throws Exception {
        connector.createRequest().setRequestParameter("to", List.of("test@localhost", "other@localhost"));
        var request = connector.createRequest();
        request.setRequestParameter("to", List.of("test@localhost", "other@localhost"));
        request.subject("List").text("x").execute();

        assertThat(server.greenMail().getReceivedMessages()).hasSize(2);
    }

    @Test
    void sendsTextAndHtmlAsAlternative() throws Exception {
        connector
                .createRequest()
                .to("test@localhost")
                .subject("Both")
                .text("plain")
                .html("<b>rich</b>")
                .execute();

        MimeMessage mail = single();
        assertThat(mail.isMimeType("multipart/alternative")).isTrue();
        MimeMultipart multipart = (MimeMultipart) mail.getContent();
        assertThat(multipart.getCount()).isEqualTo(2);
        assertThat(multipart.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(multipart.getBodyPart(1).isMimeType("text/html")).isTrue();
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(1))).contains("<b>rich</b>");
    }

    @Test
    void rendersSimpleTemplatesAndEscapesHtml() throws Exception {
        connector
                .createRequest()
                .to("test@localhost")
                .subjectTemplate("Ticket ${id}")
                .textTemplate("Hello ${customer.name}")
                .htmlTemplate("<p>Hello ${customer.name}</p>")
                .templateVariables(Map.of("id", "T-1", "customer", Map.of("name", "<Ada>")))
                .execute();

        MimeMessage mail = single();
        assertThat(mail.getSubject()).isEqualTo("Ticket T-1");
        MimeMultipart multipart = (MimeMultipart) mail.getContent();
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(0))).isEqualTo("Hello <Ada>");
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(1))).contains("<p>Hello &lt;Ada&gt;</p>");
    }

    @Test
    void rendersFreemarkerTemplates() throws Exception {
        connector
                .createRequest()
                .to("test@localhost")
                .subject("FM")
                .templateEngine("freemarker")
                .textTemplate("<#list items as i>${i}<#sep>,</#list>")
                .templateVariables(Map.of("items", List.of("a", "b")))
                .execute();

        assertThat(GreenMailUtil.getBody(single())).isEqualTo("a,b");
    }

    @Test
    void attachesFilesByNameAndByContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.txt");
        Files.writeString(file, "from disk");

        connector
                .createRequest()
                .to("test@localhost")
                .subject("Attachments")
                .text("see attached")
                .fileNames(file.toString())
                .files(Map.of(
                        "data.bin",
                        "bytes".getBytes(StandardCharsets.UTF_8),
                        "stream.txt",
                        new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8))))
                .execute();

        MimeMessage mail = single();
        assertThat(mail.isMimeType("multipart/mixed")).isTrue();
        MimeMultipart multipart = (MimeMultipart) mail.getContent();
        assertThat(multipart.getCount()).isEqualTo(4);
        assertThat(GreenMailUtil.getBody(multipart.getBodyPart(0))).isEqualTo("see attached");
        List<String> names = new java.util.ArrayList<>();
        for (int i = 1; i < multipart.getCount(); i++) {
            assertThat(multipart.getBodyPart(i).getDisposition()).isEqualToIgnoringCase(Part.ATTACHMENT);
            names.add(multipart.getBodyPart(i).getFileName());
        }
        assertThat(names).containsExactlyInAnyOrder("data.bin", "stream.txt", "report.txt");
    }

    @Test
    void setsReplyHeaders() throws Exception {
        connector
                .createRequest()
                .to("test@localhost")
                .subject("Re: x")
                .text("answer")
                .inReplyTo("<abc@example.org>")
                .execute();

        assertThat(single().getHeader("In-Reply-To")[0]).isEqualTo("<abc@example.org>");
    }

    @Test
    void failsLoudlyOnMissingRecipient() {
        assertThatThrownBy(
                        () -> connector.createRequest().subject("x").text("y").execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("'to'");
        assertThat(server.greenMail().getReceivedMessages()).isEmpty();
    }

    @Test
    void failsLoudlyOnInvalidAddressesAndMissingFiles() {
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .to("not an address @@")
                        .subject("x")
                        .text("y")
                        .execute())
                .isInstanceOf(MailConnectorException.class);
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .to("test@localhost")
                        .fileNames("/no/such/file.txt")
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("/no/such/file.txt");
    }

    @Test
    void failsLoudlyOnConflictingLiteralAndTemplate() {
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .to("test@localhost")
                        .text("a")
                        .textTemplate("b")
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void failsLoudlyOnUnresolvedTemplatePlaceholder() {
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .to("test@localhost")
                        .textTemplate("Hi ${nobody}")
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("nobody");
    }

    @Test
    void failsLoudlyWhenTheServerIsDown() {
        server.close();

        assertThatThrownBy(() -> connector
                        .createRequest()
                        .to("test@localhost")
                        .subject("x")
                        .text("y")
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("Failed to send mail");
    }
}

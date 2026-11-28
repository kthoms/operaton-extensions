/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Flags.Flag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.connect.Connectors;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailServerSupport;
import org.operaton.extensions.connector.mail.dto.Mail;

class PollMailConnectorTest {

    private MailServerSupport server;
    private PollMailConnector connector;

    @BeforeEach
    void startServer() {
        server = new MailServerSupport();
        connector = Connectors.getConnector(PollMailConnector.CONNECTOR_ID);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void returnsMailsOldestFirstWithAllFields() throws Exception {
        server.deliver("Alice <alice@example.org>", "First", "one");
        server.deliver("bob@example.org", "Second", "two");

        PollMailResponse response = connector.createRequest().execute();

        List<Mail> mails = response.getMails();
        assertThat(mails).extracting(Mail::getSubject).containsExactly("First", "Second");
        Mail first = mails.get(0);
        assertThat(first.getFrom()).contains("Alice").contains("alice@example.org");
        assertThat(first.getFromAddress()).isEqualTo("alice@example.org");
        assertThat(first.getTo()).isEqualTo("test@localhost");
        assertThat(first.getText()).isEqualToIgnoringWhitespace("one");
        assertThat(first.getMessageId()).isNotBlank();
        assertThat(response.getResponseParameter("count").equals(2)).isTrue();
    }

    @Test
    void filtersByFromSubjectLimitAndDate() throws Exception {
        server.deliver("alice@example.org", "Invoice 1", "a");
        server.deliver("alice@example.org", "Hello", "b");
        server.deliver("bob@example.org", "Invoice 2", "c");

        assertThat(connector.createRequest().from("bob@example.org").execute().getMails())
                .extracting(Mail::getSubject)
                .containsExactly("Invoice 2");
        assertThat(connector.createRequest().subject("Invoice").execute().getMails())
                .hasSize(2);
        assertThat(connector.createRequest().limit(1).execute().getMails())
                .extracting(Mail::getSubject)
                .containsExactly("Invoice 1");
        assertThat(connector
                        .createRequest()
                        .receivedSince(new Date(System.currentTimeMillis() - 86_400_000L))
                        .execute()
                        .getMails())
                .hasSize(3);
        assertThat(connector
                        .createRequest()
                        .receivedSince(new Date(System.currentTimeMillis() + 2 * 86_400_000L))
                        .execute()
                        .getMails())
                .isEmpty();
    }

    @Test
    void readingDoesNotMarkAsReadUnlessAsked() throws Exception {
        server.deliver("alice@example.org", "Read me", "a");

        assertThat(connector.createRequest().unreadOnly(true).execute().getMails())
                .hasSize(1);
        assertThat(connector.createRequest().unreadOnly(true).execute().getMails())
                .hasSize(1);

        assertThat(connector
                        .createRequest()
                        .unreadOnly(true)
                        .markAsRead(true)
                        .execute()
                        .getMails())
                .hasSize(1);
        assertThat(connector.createRequest().unreadOnly(true).execute().getMails())
                .isEmpty();
        assertThat(connector.createRequest().execute().getMails()).hasSize(1);
    }

    @Test
    void downloadsAttachmentsToTheConfiguredPath() throws Exception {
        server.deliverWithAttachment(
                "alice@example.org",
                "With file",
                "see file",
                "../../evil/../notes.txt",
                "text/plain",
                "contents".getBytes(StandardCharsets.UTF_8));

        Mail mail = connector
                .createRequest()
                .downloadAttachments(true)
                .execute()
                .getMails()
                .get(0);

        assertThat(mail.getText()).isEqualToIgnoringWhitespace("see file");
        assertThat(mail.getAttachments()).hasSize(1);
        var attachment = mail.getAttachments().get(0);
        assertThat(attachment.getFileName()).isEqualTo("notes.txt");
        assertThat(attachment.getSize()).isEqualTo(8);
        Path file = Path.of(attachment.getPath());
        assertThat(file).hasContent("contents");
        assertThat(file.toAbsolutePath())
                .startsWith(Path.of("target/test-attachments").toAbsolutePath());
        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    void canSkipDownloadingAttachments() throws Exception {
        server.deliverWithAttachment("a@example.org", "x", "y", "f.txt", "text/plain", new byte[] {1, 2, 3});

        Mail mail = connector
                .createRequest()
                .downloadAttachments(false)
                .execute()
                .getMails()
                .get(0);

        assertThat(mail.getAttachments()).hasSize(1);
        assertThat(mail.getAttachments().get(0).getPath()).isNull();
        assertThat(mail.getAttachments().get(0).getSize()).isEqualTo(3);
    }

    @Test
    void deletedMailsAreNotReturned() throws Exception {
        server.deliver("alice@example.org", "Gone", "a");
        server.deliver("alice@example.org", "Stays", "b");
        server.greenMail()
                .getManagers()
                .getImapHostManager()
                .getInbox(server.greenMail().getUserManager().getUserByEmail(MailServerSupport.USER))
                .getMessages()
                .get(0)
                .setFlag(Flag.DELETED, true);

        assertThat(connector.createRequest().execute().getMails())
                .extracting(Mail::getSubject)
                .containsExactly("Stays");
    }

    @Test
    void failsLoudlyOnUnknownFolderAndBadLimit() {
        assertThatThrownBy(() -> connector.createRequest().folder("Nope").execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("'Nope' does not exist");
        assertThatThrownBy(() -> connector.createRequest().limit(0).execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void failsLoudlyWhenTheServerIsDown() {
        server.close();

        assertThatThrownBy(() -> connector.createRequest().execute()).isInstanceOf(MailConnectorException.class);
    }
}

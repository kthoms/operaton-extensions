/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.delete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.connect.Connectors;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailServerSupport;
import org.operaton.extensions.connector.mail.dto.Mail;
import org.operaton.extensions.connector.mail.poll.PollMailConnector;

class DeleteMailConnectorTest {

    private MailServerSupport server;
    private DeleteMailConnector connector;
    private PollMailConnector poller;

    @BeforeEach
    void startServer() throws Exception {
        server = new MailServerSupport();
        connector = Connectors.getConnector(DeleteMailConnector.CONNECTOR_ID);
        poller = Connectors.getConnector(PollMailConnector.CONNECTOR_ID);
        server.deliver("a@example.org", "one", "1");
        server.deliver("a@example.org", "two", "2");
        server.deliver("a@example.org", "three", "3");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private List<String> subjects() {
        return poller.createRequest().execute().getMails().stream()
                .map(Mail::getSubject)
                .toList();
    }

    @Test
    void deletesPolledMails() {
        List<Mail> mails = poller.createRequest().execute().getMails();

        connector.createRequest().mails(mails.get(0), mails.get(2)).execute();

        assertThat(subjects()).containsExactly("two");
        // really expunged from the folder, not only flagged
        assertThat(server.greenMail().getReceivedMessages()).hasSize(1);
    }

    @Test
    void deletesByMessageId() {
        String id = poller.createRequest().execute().getMails().get(1).getMessageId();

        connector.createRequest().messageIds(id).execute();

        assertThat(subjects()).containsExactly("one", "three");
    }

    @Test
    void deletesByMessageNumber() {
        connector.createRequest().messageNumbers(1, 2).execute();

        assertThat(subjects()).containsExactly("three");
    }

    @Test
    void failsLoudlyOnUnknownIdAndLeavesMailsUntouched() {
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .messageIds("<nope@example.org>")
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("<nope@example.org>");
        assertThat(subjects()).hasSize(3);
    }

    @Test
    void failsLoudlyWithoutOrWithAmbiguousSelectors() {
        assertThatThrownBy(() -> connector.createRequest().execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> connector
                        .createRequest()
                        .messageIds("<a>")
                        .messageNumbers(1)
                        .execute())
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void failsLoudlyOnOutOfRangeMessageNumber() {
        assertThatThrownBy(() -> connector.createRequest().messageNumbers(99).execute())
                .isInstanceOf(MailConnectorException.class);
        assertThat(subjects()).hasSize(3);
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.extensions.connector.mail.MailConnectorException;

class MailSettingsTest {

    @Test
    void failsWithoutAnyHost() {
        assertThatThrownBy(() -> MailSettings.fromProperties(new Properties()))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("no host configured");
    }

    @Test
    void appliesDefaults() {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "smtp.example.org");

        MailSettings settings = MailSettings.fromProperties(properties);

        assertThat(settings.pollFolder()).isEqualTo("INBOX");
        assertThat(settings.downloadAttachments()).isTrue();
        assertThat(settings.attachmentPath()).isEqualTo("attachments");
        assertThat(settings.sender()).isNull();
        assertThat(settings.sessionProperties())
                .containsEntry("mail.transport.protocol", "smtp")
                .containsEntry("mail.store.protocol", "imap")
                .containsEntry("mail.smtp.connectiontimeout", "10000")
                .containsEntry("mail.imap.peek", "true");
    }

    @Test
    void doesNotLeakCredentialsInToString() {
        MailSettings settings = MailSettings.builder()
                .smtp("smtp.example.org", 587, Security.STARTTLS, "robot", "s3cr3t-smtp")
                .imap("imap.example.org", 993, Security.SSL, "robot", "s3cr3t-imap")
                .build();

        assertThat(settings.toString())
                .contains("mail.smtp.host=smtp.example.org")
                .contains("mail.smtp.password=***")
                .contains("mail.imap.password=***")
                .doesNotContain("s3cr3t");
    }

    @Test
    void builderMapsSecurityOntoJakartaMailProperties() {
        MailSettings settings = MailSettings.builder()
                .smtp("smtp.example.org", 587, Security.STARTTLS, "robot", "pw")
                .imap("imap.example.org", 993, Security.SSL, null, null)
                .sender("bpm@example.org")
                .senderAlias("BPM")
                .pollFolder("Tickets")
                .downloadAttachments(false)
                .attachmentPath("/var/attachments")
                .build();

        assertThat(settings.sessionProperties())
                .containsEntry("mail.smtp.host", "smtp.example.org")
                .containsEntry("mail.smtp.port", "587")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.imap.ssl.enable", "true")
                .doesNotContainKey("mail.imap.auth");
        assertThat(settings.sender()).isEqualTo("bpm@example.org");
        assertThat(settings.senderAlias()).isEqualTo("BPM");
        assertThat(settings.pollFolder()).isEqualTo("Tickets");
        assertThat(settings.downloadAttachments()).isFalse();
        assertThat(settings.attachmentPath()).isEqualTo("/var/attachments");
    }

    @Test
    void loadsFromClasspathAndFile(@TempDir Path dir) throws Exception {
        assertThat(MailSettings.load("classpath:mail-config.properties").sender())
                .isEqualTo("bpm@localhost");

        Path file = dir.resolve("mail.properties");
        Files.writeString(file, "mail.imap.host=imap.example.org\nmail.poll.folder=Support\n");
        assertThat(MailSettings.load(file.toString()).pollFolder()).isEqualTo("Support");
    }

    @Test
    void missingFileFailsLoudly(@TempDir Path dir) {
        assertThatThrownBy(
                        () -> MailSettings.load(dir.resolve("nope.properties").toString()))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> MailSettings.load("classpath:nope.properties"))
                .isInstanceOf(MailConnectorException.class)
                .hasMessageContaining("nope.properties");
    }

    @Test
    void settingsAreImmutableCopies() {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "a");
        MailSettings settings = MailSettings.fromProperties(properties);

        properties.setProperty("mail.smtp.host", "b");
        settings.sessionProperties().setProperty("mail.smtp.host", "c");

        assertThat(settings.sessionProperties().getProperty("mail.smtp.host")).isEqualTo("a");
    }
}

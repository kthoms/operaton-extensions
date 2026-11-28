/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.service;

import jakarta.mail.Authenticator;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.util.Objects;
import java.util.Properties;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.config.MailSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends mail and runs actions on IMAP folders. Every operation opens its own connection and closes it afterwards, so
 * an instance is thread-safe and holds no connection between calls.
 */
public final class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    private final MailSettings settings;
    private final Session session;

    public MailService(MailSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        Properties properties = settings.sessionProperties();
        this.session = Session.getInstance(properties, new PropertiesAuthenticator(properties));
    }

    public MailSettings settings() {
        return settings;
    }

    /** A new, empty message bound to this service's session. */
    public MimeMessage newMessage() {
        return new MimeMessage(session);
    }

    /** Sends the message to all its recipients. Fails with the transport's reason. */
    public void send(MimeMessage message) {
        try (Transport transport = session.getTransport()) {
            transport.connect();
            message.saveChanges();
            transport.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException e) {
            throw new MailConnectorException("Failed to send mail '" + subjectOf(message) + "': " + e.getMessage(), e);
        }
    }

    /**
     * Opens {@code folderName}, runs {@code action} and closes the folder and connection.
     *
     * @param readWrite open read-write (needed to set flags or delete)
     * @param expunge remove messages flagged DELETED when the folder is closed
     */
    public <T> T withFolder(String folderName, boolean readWrite, boolean expunge, FolderAction<T> action) {
        try (Store store = session.getStore()) {
            store.connect();
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                throw new MailConnectorException("Mail folder '" + folderName + "' does not exist");
            }
            folder.open(readWrite ? Folder.READ_WRITE : Folder.READ_ONLY);
            boolean completed = false;
            try {
                T result = action.apply(folder);
                completed = true;
                return result;
            } finally {
                closeFolder(folder, completed && readWrite && expunge);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MailConnectorException("Mail folder '" + folderName + "' failed: " + e.getMessage(), e);
        }
    }

    private static void closeFolder(Folder folder, boolean expunge) {
        try {
            if (folder.isOpen()) {
                folder.close(expunge);
            }
        } catch (MessagingException e) {
            // the action's own result or failure is what the caller needs; closing is best effort
            LOG.warn("Could not close mail folder '{}': {}", folder.getFullName(), e.getMessage());
        }
    }

    private static String subjectOf(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (MessagingException e) {
            return "?";
        }
    }

    /** Looks up {@code mail.<protocol>.user} / {@code .password}, then {@code mail.user} / {@code mail.password}. */
    private static final class PropertiesAuthenticator extends Authenticator {

        private final Properties properties;

        PropertiesAuthenticator(Properties properties) {
            this.properties = properties;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            String protocol = getRequestingProtocol();
            String user = firstNonNull(
                    properties.getProperty("mail." + protocol + ".user"), properties.getProperty("mail.user"));
            String password = firstNonNull(
                    properties.getProperty("mail." + protocol + ".password"), properties.getProperty("mail.password"));
            if (user == null || password == null) {
                return null;
            }
            return new PasswordAuthentication(user, password);
        }

        private static String firstNonNull(String a, String b) {
            return a != null ? a : b;
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import org.operaton.extensions.connector.mail.MailConnectorException;

/**
 * Immutable configuration of the mail connectors: the Jakarta Mail session properties (`mail.smtp.*`,
 * `mail.imap.*`, `mail.user`, `mail.password`) plus the extension's own defaults.
 *
 * <p>Instances are thread-safe. Credentials are never part of {@link #toString()}.
 *
 * <p>Extension keys in a properties file: `mail.sender`, `mail.sender.alias`, `mail.poll.folder` (default
 * `INBOX`), `mail.attachment.download` (default `true`), `mail.attachment.path` (default `attachments`).
 */
public final class MailSettings {

    public static final String PROPERTY_SENDER = "mail.sender";
    public static final String PROPERTY_SENDER_ALIAS = "mail.sender.alias";
    public static final String PROPERTY_POLL_FOLDER = "mail.poll.folder";
    public static final String PROPERTY_ATTACHMENT_DOWNLOAD = "mail.attachment.download";
    public static final String PROPERTY_ATTACHMENT_PATH = "mail.attachment.path";

    public static final String DEFAULT_POLL_FOLDER = "INBOX";
    public static final String DEFAULT_ATTACHMENT_PATH = "attachments";

    /** System property naming the configuration file (`classpath:...` or a file path). */
    public static final String SYSTEM_PROPERTY_CONFIG = "operaton.extensions.connector.mail.config";

    /** Environment variable naming the configuration file. */
    public static final String ENV_CONFIG = "OPERATON_MAIL_CONFIG";

    public static final String DEFAULT_CONFIG = "classpath:mail-config.properties";

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final Properties sessionProperties;
    private final String sender;
    private final String senderAlias;
    private final String pollFolder;
    private final boolean downloadAttachments;
    private final String attachmentPath;

    private MailSettings(Properties properties) {
        Properties copy = new Properties();
        for (String name : properties.stringPropertyNames()) {
            copy.setProperty(name, properties.getProperty(name));
        }
        copy.putIfAbsent("mail.store.protocol", "imap");
        copy.putIfAbsent("mail.transport.protocol", "smtp");
        // reading a mail must not change its state: marking as read is an explicit decision
        copy.putIfAbsent("mail.imap.peek", "true");
        for (String protocol : new String[] {"smtp", "imap"}) {
            // never wait forever for a mail server
            copy.putIfAbsent("mail." + protocol + ".connectiontimeout", "10000");
            copy.putIfAbsent("mail." + protocol + ".timeout", "30000");
            copy.putIfAbsent("mail." + protocol + ".writetimeout", "30000");
        }
        this.sessionProperties = copy;
        this.sender = blankToNull(copy.getProperty(PROPERTY_SENDER));
        this.senderAlias = blankToNull(copy.getProperty(PROPERTY_SENDER_ALIAS));
        String folder = blankToNull(copy.getProperty(PROPERTY_POLL_FOLDER));
        this.pollFolder = folder == null ? DEFAULT_POLL_FOLDER : folder;
        this.downloadAttachments = Boolean.parseBoolean(copy.getProperty(PROPERTY_ATTACHMENT_DOWNLOAD, "true"));
        String path = blankToNull(copy.getProperty(PROPERTY_ATTACHMENT_PATH));
        this.attachmentPath = path == null ? DEFAULT_ATTACHMENT_PATH : path;
        validate();
    }

    private void validate() {
        boolean hasHost = false;
        for (String name : sessionProperties.stringPropertyNames()) {
            if (name.matches("mail\\.(smtps?|imaps?|pop3s?)?\\.?host")
                    && blankToNull(sessionProperties.getProperty(name)) != null) {
                hasHost = true;
            }
        }
        if (!hasHost) {
            throw new MailConnectorException("Invalid mail configuration: no host configured. Set at least one of "
                    + "mail.smtp.host (sending) or mail.imap.host (polling/deleting).");
        }
    }

    /** Settings from a properties object. Fails if no SMTP or IMAP host is configured. */
    public static MailSettings fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return new MailSettings(properties);
    }

    /**
     * Settings from a properties file at {@code location}: `classpath:name` or a file system path. Fails if the file
     * is missing or invalid.
     */
    public static MailSettings load(String location) {
        Objects.requireNonNull(location, "location");
        Properties properties = new Properties();
        try (InputStream in = open(location)) {
            properties.load(in);
        } catch (IOException e) {
            throw new MailConnectorException("Unable to read mail configuration from '" + location + "'", e);
        }
        try {
            return fromProperties(properties);
        } catch (MailConnectorException e) {
            throw new MailConnectorException(e.getMessage() + " (file: " + location + ")", e);
        }
    }

    /** Settings from the system property, environment variable or default location, in this order. */
    public static MailSettings loadDefault() {
        String location = System.getProperty(SYSTEM_PROPERTY_CONFIG);
        if (blankToNull(location) == null) {
            location = System.getenv(ENV_CONFIG);
        }
        if (blankToNull(location) == null) {
            location = DEFAULT_CONFIG;
        }
        return load(location);
    }

    private static InputStream open(String location) throws IOException {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            String resource = location.substring(CLASSPATH_PREFIX.length());
            if (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            InputStream in =
                    (loader != null ? loader : MailSettings.class.getClassLoader()).getResourceAsStream(resource);
            if (in == null) {
                throw new MailConnectorException("Mail configuration not found on the classpath: '" + resource
                        + "'. Register a MailConnectorPlugin, set -D" + SYSTEM_PROPERTY_CONFIG + " or provide "
                        + DEFAULT_CONFIG + ".");
            }
            return in;
        }
        Path path = Paths.get(location);
        if (!Files.isRegularFile(path)) {
            throw new MailConnectorException("Mail configuration file not found: " + path.toAbsolutePath());
        }
        return Files.newInputStream(path);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A copy of the Jakarta Mail session properties (including credentials: handle with care). */
    public Properties sessionProperties() {
        Properties copy = new Properties();
        for (String name : sessionProperties.stringPropertyNames()) {
            copy.setProperty(name, sessionProperties.getProperty(name));
        }
        return copy;
    }

    /** Default sender, or {@code null}. */
    public String sender() {
        return sender;
    }

    /** Default sender display name, or {@code null}. */
    public String senderAlias() {
        return senderAlias;
    }

    /** Default folder for polling and deleting. */
    public String pollFolder() {
        return pollFolder;
    }

    /** Whether polled attachments are written to {@link #attachmentPath()} by default. */
    public boolean downloadAttachments() {
        return downloadAttachments;
    }

    /** Directory attachments of polled mails are downloaded to. */
    public String attachmentPath() {
        return attachmentPath;
    }

    @Override
    public String toString() {
        List<String> shown = new ArrayList<>();
        for (String name : new TreeSet<>(sessionProperties.stringPropertyNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean secret = lower.contains("password") || lower.contains("secret") || lower.contains("token");
            shown.add(name + "=" + (secret ? "***" : sessionProperties.getProperty(name)));
        }
        return "MailSettings" + Collections.unmodifiableList(shown);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Builds {@link MailSettings} without a properties file. */
    public static final class Builder {

        private final Properties properties = new Properties();

        private Builder() {}

        /** Configures sending. {@code user} and {@code password} may be {@code null} for an open relay. */
        public Builder smtp(String host, Integer port, Security security, String user, String password) {
            return endpoint("smtp", host, port, security, user, password);
        }

        /** Configures polling and deleting over IMAP. */
        public Builder imap(String host, Integer port, Security security, String user, String password) {
            return endpoint("imap", host, port, security, user, password);
        }

        private Builder endpoint(
                String protocol, String host, Integer port, Security security, String user, String password) {
            String prefix = "mail." + protocol + ".";
            if (host != null && !host.isBlank()) {
                properties.setProperty(prefix + "host", host.trim());
            }
            if (port != null) {
                properties.setProperty(prefix + "port", port.toString());
            }
            Security mode = security == null ? Security.NONE : security;
            switch (mode) {
                case SSL -> properties.setProperty(prefix + "ssl.enable", "true");
                case STARTTLS -> {
                    properties.setProperty(prefix + "starttls.enable", "true");
                    properties.setProperty(prefix + "starttls.required", "true");
                }
                case NONE -> {}
            }
            if (user != null && !user.isBlank()) {
                properties.setProperty(prefix + "user", user);
                properties.setProperty(prefix + "auth", "true");
                if (password != null) {
                    properties.setProperty(prefix + "password", password);
                }
            }
            return this;
        }

        public Builder sender(String sender) {
            return put(PROPERTY_SENDER, sender);
        }

        public Builder senderAlias(String senderAlias) {
            return put(PROPERTY_SENDER_ALIAS, senderAlias);
        }

        public Builder pollFolder(String folder) {
            return put(PROPERTY_POLL_FOLDER, folder);
        }

        public Builder downloadAttachments(boolean download) {
            return put(PROPERTY_ATTACHMENT_DOWNLOAD, Boolean.toString(download));
        }

        public Builder attachmentPath(String path) {
            return put(PROPERTY_ATTACHMENT_PATH, path);
        }

        /** Any further Jakarta Mail property; overrides what the typed methods set. */
        public Builder property(String name, String value) {
            return put(name, value);
        }

        private Builder put(String name, String value) {
            if (value != null) {
                properties.setProperty(name, value);
            }
            return this;
        }

        public MailSettings build() {
            return new MailSettings(properties);
        }
    }
}

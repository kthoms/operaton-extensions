/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.connector.mail.config.Security;
import org.operaton.extensions.connector.mail.inbound.InboundSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.connector.mail.*}. Credentials are never logged. */
@ConfigurationProperties(prefix = "operaton.extensions.connector.mail")
public class MailConnectorProperties {

    /** Whether the mail connector auto-configuration is active. */
    private boolean enabled = true;

    /** Optional properties file ({@code classpath:...} or path) used instead of the typed properties. */
    private String config;

    /** Default sender address. */
    private String sender;

    /** Default sender display name. */
    private String senderAlias;

    /** Default folder for polling and deleting. */
    private String folder = "INBOX";

    /** Whether attachments of polled mails are downloaded. */
    private boolean downloadAttachments = true;

    /** Directory attachments are downloaded to. */
    private String attachmentPath = "attachments";

    /** Further Jakarta Mail properties, for example {@code mail.smtp.connectiontimeout}. */
    private Map<String, String> properties = new LinkedHashMap<>();

    private final Endpoint smtp = new Endpoint();
    private final Endpoint imap = new Endpoint();
    private final Inbound inbound = new Inbound();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSenderAlias() {
        return senderAlias;
    }

    public void setSenderAlias(String senderAlias) {
        this.senderAlias = senderAlias;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public boolean isDownloadAttachments() {
        return downloadAttachments;
    }

    public void setDownloadAttachments(boolean downloadAttachments) {
        this.downloadAttachments = downloadAttachments;
    }

    public String getAttachmentPath() {
        return attachmentPath;
    }

    public void setAttachmentPath(String attachmentPath) {
        this.attachmentPath = attachmentPath;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public Endpoint getSmtp() {
        return smtp;
    }

    public Endpoint getImap() {
        return imap;
    }

    public Inbound getInbound() {
        return inbound;
    }

    /** One mail server connection. */
    public static class Endpoint {

        /** Host name; leave empty if the connection is not used. */
        private String host;

        /** Port; the protocol default when empty. */
        private Integer port;

        /** Transport security: none, starttls or ssl. */
        private Security tls = Security.NONE;

        /** User name; enables authentication. */
        private String username;

        /** Password. */
        private String password;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public Security getTls() {
            return tls;
        }

        public void setTls(Security tls) {
            this.tls = tls;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** The inbound poller. */
    public static class Inbound {

        /** Whether to poll for mails and start or correlate processes. */
        private boolean enabled;

        /** Folder to watch; defaults to {@code folder}. */
        private String folder;

        /** Time between two polls. */
        private Duration pollInterval = Duration.ofSeconds(30);

        /** What happens to a handled mail: mark-seen, delete or move. */
        private InboundSettings.Completion completion = InboundSettings.Completion.MARK_SEEN;

        /** Target folder for completion=move. */
        private String processedFolder;

        /** Prefix of the process variables created from a mail. */
        private String variablePrefix = InboundSettings.DEFAULT_VARIABLE_PREFIX;

        /** Maximum number of mails handled per poll. */
        private int batchSize = 50;

        /** Routes, tried in order. */
        private List<Route> routes = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFolder() {
            return folder;
        }

        public void setFolder(String folder) {
            this.folder = folder;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public InboundSettings.Completion getCompletion() {
            return completion;
        }

        public void setCompletion(InboundSettings.Completion completion) {
            this.completion = completion;
        }

        public String getProcessedFolder() {
            return processedFolder;
        }

        public void setProcessedFolder(String processedFolder) {
            this.processedFolder = processedFolder;
        }

        public String getVariablePrefix() {
            return variablePrefix;
        }

        public void setVariablePrefix(String variablePrefix) {
            this.variablePrefix = variablePrefix;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes;
        }
    }

    /** See {@code MailRoute}. */
    public static class Route {

        /** Unique route name. */
        private String name;

        /** Regular expression for the sender address. */
        private String fromPattern;

        /** Regular expression for the subject. */
        private String subjectPattern;

        /** Regular expression whose group 1 of the subject is the business key. */
        private String businessKeyPattern;

        /** Process definition to start. */
        private String processDefinitionKey;

        /** Message to correlate. */
        private String messageName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFromPattern() {
            return fromPattern;
        }

        public void setFromPattern(String fromPattern) {
            this.fromPattern = fromPattern;
        }

        public String getSubjectPattern() {
            return subjectPattern;
        }

        public void setSubjectPattern(String subjectPattern) {
            this.subjectPattern = subjectPattern;
        }

        public String getBusinessKeyPattern() {
            return businessKeyPattern;
        }

        public void setBusinessKeyPattern(String businessKeyPattern) {
            this.businessKeyPattern = businessKeyPattern;
        }

        public String getProcessDefinitionKey() {
            return processDefinitionKey;
        }

        public void setProcessDefinitionKey(String processDefinitionKey) {
            this.processDefinitionKey = processDefinitionKey;
        }

        public String getMessageName() {
            return messageName;
        }

        public void setMessageName(String messageName) {
            this.messageName = messageName;
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier.spring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.plugin.incident-notifier.*}; mapped one to one onto the plugin. */
@ConfigurationProperties(prefix = "operaton.extensions.plugin.incident-notifier")
public class IncidentNotifierProperties {

    /** Whether the plugin is registered. */
    private boolean enabled = true;
    /** Incident types to observe. */
    private List<String> incidentTypes = List.of("failedJob", "failedExternalTask");
    /** At most one digest per process definition in this window; zero notifies every incident. */
    private Duration throttle = Duration.ofSeconds(60);
    /** Base URL of the web apps for Cockpit links, e.g. http://localhost:8080/operaton. */
    private String cockpitBaseUrl = "";

    private int maxIncidentsPerMessage = 20;
    private int maxBufferedIncidents = 500;
    private int senderThreads = 2;
    /** Zone id for rendered times; system zone when unset. */
    private String timeZone;
    /** Process variable with per-instance mail recipients. */
    private String recipientVariable = "incidentNotificationReceiver";
    /** Process variable with per-instance mail cc recipients. */
    private String ccVariable = "incidentNotificationCc";

    private final Template template = new Template();
    private final Email email = new Email();
    private final Webhook webhook = new Webhook();
    private final Url slack = new Url();
    private final Url teams = new Url();

    public static class Template {
        /** Subject template; plugin default when unset. */
        private String subject;
        /** Body template; plugin default when unset. */
        private String body;
        /** Template of one incident; plugin default when unset. */
        private String incident;

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String getIncident() {
            return incident;
        }

        public void setIncident(String incident) {
            this.incident = incident;
        }
    }

    public static class Email {
        /** SMTP host; enables the email channel. */
        private String host;

        private int port = 587;
        private String username;
        private String password;
        private String from;
        /** Default recipients. */
        private String to;

        private String cc;
        private boolean starttls = true;
        private boolean starttlsRequired = false;
        private boolean ssl = false;
        private Duration timeout = Duration.ofSeconds(10);

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
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

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getCc() {
            return cc;
        }

        public void setCc(String cc) {
            this.cc = cc;
        }

        public boolean isStarttls() {
            return starttls;
        }

        public void setStarttls(boolean starttls) {
            this.starttls = starttls;
        }

        public boolean isStarttlsRequired() {
            return starttlsRequired;
        }

        public void setStarttlsRequired(boolean starttlsRequired) {
            this.starttlsRequired = starttlsRequired;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Url {
        /** Webhook URL; enables the channel. */
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class Webhook extends Url {
        /** Extra HTTP headers, e.g. Authorization. */
        private Map<String, String> headers = new LinkedHashMap<>();

        private Duration timeout = Duration.ofSeconds(10);

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getIncidentTypes() {
        return incidentTypes;
    }

    public void setIncidentTypes(List<String> incidentTypes) {
        this.incidentTypes = incidentTypes;
    }

    public Duration getThrottle() {
        return throttle;
    }

    public void setThrottle(Duration throttle) {
        this.throttle = throttle;
    }

    public String getCockpitBaseUrl() {
        return cockpitBaseUrl;
    }

    public void setCockpitBaseUrl(String cockpitBaseUrl) {
        this.cockpitBaseUrl = cockpitBaseUrl;
    }

    public int getMaxIncidentsPerMessage() {
        return maxIncidentsPerMessage;
    }

    public void setMaxIncidentsPerMessage(int maxIncidentsPerMessage) {
        this.maxIncidentsPerMessage = maxIncidentsPerMessage;
    }

    public int getMaxBufferedIncidents() {
        return maxBufferedIncidents;
    }

    public void setMaxBufferedIncidents(int maxBufferedIncidents) {
        this.maxBufferedIncidents = maxBufferedIncidents;
    }

    public int getSenderThreads() {
        return senderThreads;
    }

    public void setSenderThreads(int senderThreads) {
        this.senderThreads = senderThreads;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getRecipientVariable() {
        return recipientVariable;
    }

    public void setRecipientVariable(String recipientVariable) {
        this.recipientVariable = recipientVariable;
    }

    public String getCcVariable() {
        return ccVariable;
    }

    public void setCcVariable(String ccVariable) {
        this.ccVariable = ccVariable;
    }

    public Template getTemplate() {
        return template;
    }

    public Email getEmail() {
        return email;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Url getSlack() {
        return slack;
    }

    public Url getTeams() {
        return teams;
    }
}

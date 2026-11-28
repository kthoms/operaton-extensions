/*
 * Copyright 2020 “J-IT“ IT-Dienstleistungs GesmbH, Vienna, Austria
 * Modifications Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/email-incident-notification-plugin (Apache-2.0)
 */

package org.operaton.extensions.plugin.incidentnotifier;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.incident.IncidentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that notifies operators about incidents by mail, generic webhook, Slack and
 * Microsoft Teams.
 *
 * <p>Generalised from the plugin of camunda-community-hub/email-incident-notification-plugin. The
 * configured incident handlers ({@code failedJob} and {@code failedExternalTask} by default) are
 * decorated, their behaviour is unchanged. Incidents are collected after the transaction committed,
 * grouped per process definition within a throttle window and delivered on separate threads. A
 * failing notification is logged and counted, it never affects the engine.
 *
 * <p>All scalar properties can be set from {@code bpm-platform.xml} or {@code processEnginePlugins};
 * the configuration is validated in {@link #preInit}, an invalid or empty configuration fails the
 * engine start. The plugin owns threads: call {@link #close()} when the engine is shut down (the
 * Spring Boot starter does it). One plugin instance serves one engine.
 */
public class IncidentNotifierPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentNotifierPlugin.class);
    private static final String PREFIX = "incident-notifier: ";

    private String incidentTypes = "failedJob,failedExternalTask";
    private long throttleMillis = 60_000L;
    private String cockpitBaseUrl = "";
    private int maxIncidentsPerMessage = 20;
    private int maxBufferedIncidents = 500;
    private int senderThreads = 2;
    private String recipientVariable = "incidentNotificationReceiver";
    private String ccVariable = "incidentNotificationCc";
    private String timeZone;
    private String subject = MessageRenderer.DEFAULT_SUBJECT;
    private String bodyTemplate = MessageRenderer.DEFAULT_BODY_TEMPLATE;
    private String incidentTemplate = MessageRenderer.DEFAULT_INCIDENT_TEMPLATE;
    private String mailHost;
    private int mailPort = 587;
    private String mailUsername;
    private String mailPassword;
    private String mailFrom;
    private String mailTo;
    private String mailCc;
    private boolean mailStartTls = true;
    private boolean mailStartTlsRequired = false;
    private boolean mailSsl = false;
    private int mailTimeoutMillis = 10_000;
    private String webhookUrl;
    private String slackWebhookUrl;
    private String teamsWebhookUrl;
    private int webhookTimeoutMillis = 10_000;
    private Map<String, String> webhookHeaders = new LinkedHashMap<>();

    private final List<NotificationChannel> additionalChannels = new CopyOnWriteArrayList<>();
    private final NotificationStatistics statistics = new NotificationStatistics();
    private List<String> types = List.of();
    private List<NotificationChannel> channels = List.of();
    private volatile IncidentDispatcher dispatcher;
    private boolean initialized;

    /** Adds a custom channel next to the configured ones; call before the engine is built. */
    public void addChannel(NotificationChannel channel) {
        if (channel == null) {
            throw new IllegalArgumentException(PREFIX + "channel must not be null");
        }
        additionalChannels.add(channel);
    }

    /** Counters of observed, delivered, failed and dropped notifications. */
    public NotificationStatistics getStatistics() {
        return statistics;
    }

    /** Extra HTTP headers (for example {@code Authorization}) sent to the generic webhook. */
    public Map<String, String> getWebhookHeaders() {
        return webhookHeaders;
    }

    public void setWebhookHeaders(Map<String, String> webhookHeaders) {
        this.webhookHeaders = webhookHeaders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(webhookHeaders);
    }

    /** Comma separated incident types to observe. */
    public String getIncidentTypes() {
        return incidentTypes;
    }

    public void setIncidentTypes(String incidentTypes) {
        this.incidentTypes = incidentTypes;
    }

    /** Throttle window per process definition in milliseconds; 0 notifies every incident on its own. */
    public long getThrottleMillis() {
        return throttleMillis;
    }

    public void setThrottleMillis(long throttleMillis) {
        this.throttleMillis = throttleMillis;
    }

    /** Base URL of the web apps, for example {@code http://localhost:8080/operaton}; empty for no Cockpit links. */
    public String getCockpitBaseUrl() {
        return cockpitBaseUrl;
    }

    public void setCockpitBaseUrl(String cockpitBaseUrl) {
        this.cockpitBaseUrl = cockpitBaseUrl;
    }

    /** Most incidents rendered into one message; the rest is summarised. */
    public int getMaxIncidentsPerMessage() {
        return maxIncidentsPerMessage;
    }

    public void setMaxIncidentsPerMessage(int maxIncidentsPerMessage) {
        this.maxIncidentsPerMessage = maxIncidentsPerMessage;
    }

    /** Most incidents buffered per process definition inside a throttle window; the oldest are dropped beyond it. */
    public int getMaxBufferedIncidents() {
        return maxBufferedIncidents;
    }

    public void setMaxBufferedIncidents(int maxBufferedIncidents) {
        this.maxBufferedIncidents = maxBufferedIncidents;
    }

    /** Threads delivering notifications. */
    public int getSenderThreads() {
        return senderThreads;
    }

    public void setSenderThreads(int senderThreads) {
        this.senderThreads = senderThreads;
    }

    /** Process variable with the mail recipients of one process instance. */
    public String getRecipientVariable() {
        return recipientVariable;
    }

    public void setRecipientVariable(String recipientVariable) {
        this.recipientVariable = recipientVariable;
    }

    /** Process variable with the mail cc recipients of one process instance. */
    public String getCcVariable() {
        return ccVariable;
    }

    public void setCcVariable(String ccVariable) {
        this.ccVariable = ccVariable;
    }

    /** Zone id used to render times, default is the system zone. */
    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    /** Subject template (mail subject, title of Slack/Teams/webhook messages). */
    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    /** Body template, {@code @INCIDENTS} is replaced by the rendered incidents. */
    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    /** Template of one incident. */
    public String getIncidentTemplate() {
        return incidentTemplate;
    }

    public void setIncidentTemplate(String incidentTemplate) {
        this.incidentTemplate = incidentTemplate;
    }

    /** SMTP host; setting it enables the email channel. */
    public String getMailHost() {
        return mailHost;
    }

    public void setMailHost(String mailHost) {
        this.mailHost = mailHost;
    }

    /** SMTP port. */
    public int getMailPort() {
        return mailPort;
    }

    public void setMailPort(int mailPort) {
        this.mailPort = mailPort;
    }

    /** SMTP user; unset means no authentication. */
    public String getMailUsername() {
        return mailUsername;
    }

    public void setMailUsername(String mailUsername) {
        this.mailUsername = mailUsername;
    }

    /** SMTP password. */
    public String getMailPassword() {
        return mailPassword;
    }

    public void setMailPassword(String mailPassword) {
        this.mailPassword = mailPassword;
    }

    /** Sender address. */
    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    /** Default recipients (comma separated), used when the process instance sets no recipient variable. */
    public String getMailTo() {
        return mailTo;
    }

    public void setMailTo(String mailTo) {
        this.mailTo = mailTo;
    }

    /** Default cc recipients. */
    public String getMailCc() {
        return mailCc;
    }

    public void setMailCc(String mailCc) {
        this.mailCc = mailCc;
    }

    /** Use STARTTLS when the server offers it. */
    public boolean getMailStartTls() {
        return mailStartTls;
    }

    public void setMailStartTls(boolean mailStartTls) {
        this.mailStartTls = mailStartTls;
    }

    /** Refuse to send when the server does not offer STARTTLS. */
    public boolean getMailStartTlsRequired() {
        return mailStartTlsRequired;
    }

    public void setMailStartTlsRequired(boolean mailStartTlsRequired) {
        this.mailStartTlsRequired = mailStartTlsRequired;
    }

    /** Connect with implicit TLS (SMTPS). */
    public boolean getMailSsl() {
        return mailSsl;
    }

    public void setMailSsl(boolean mailSsl) {
        this.mailSsl = mailSsl;
    }

    /** SMTP connect, read and write timeout. */
    public int getMailTimeoutMillis() {
        return mailTimeoutMillis;
    }

    public void setMailTimeoutMillis(int mailTimeoutMillis) {
        this.mailTimeoutMillis = mailTimeoutMillis;
    }

    /** URL of a generic JSON webhook; setting it enables that channel. */
    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /** Slack incoming webhook URL; setting it enables that channel. */
    public String getSlackWebhookUrl() {
        return slackWebhookUrl;
    }

    public void setSlackWebhookUrl(String slackWebhookUrl) {
        this.slackWebhookUrl = slackWebhookUrl;
    }

    /** Microsoft Teams webhook (Workflows) URL; setting it enables that channel. */
    public String getTeamsWebhookUrl() {
        return teamsWebhookUrl;
    }

    public void setTeamsWebhookUrl(String teamsWebhookUrl) {
        this.teamsWebhookUrl = teamsWebhookUrl;
    }

    /** HTTP connect and request timeout of the webhook channels. */
    public int getWebhookTimeoutMillis() {
        return webhookTimeoutMillis;
    }

    public void setWebhookTimeoutMillis(int webhookTimeoutMillis) {
        this.webhookTimeoutMillis = webhookTimeoutMillis;
    }

    @Override
    public synchronized void preInit(ProcessEngineConfigurationImpl configuration) {
        if (initialized) {
            throw new IllegalStateException(
                    PREFIX + "this plugin instance already serves an engine; create one plugin per engine");
        }
        types = parseTypes();
        if (throttleMillis < 0) {
            throw invalid("throttleMillis must not be negative: " + throttleMillis);
        }
        if (maxBufferedIncidents < 1) {
            throw invalid("maxBufferedIncidents must be at least 1: " + maxBufferedIncidents);
        }
        if (senderThreads < 1) {
            throw invalid("senderThreads must be at least 1: " + senderThreads);
        }
        if (webhookTimeoutMillis < 1 || mailTimeoutMillis < 1) {
            throw invalid("timeouts must be positive");
        }
        if (isBlank(recipientVariable) || isBlank(ccVariable)) {
            throw invalid("recipientVariable and ccVariable must not be blank");
        }
        cockpitBaseUrl = cockpitBaseUrl == null ? "" : cockpitBaseUrl.strip().replaceAll("/+$", "");
        if (!cockpitBaseUrl.isEmpty()) {
            requireHttpUrl("cockpitBaseUrl", cockpitBaseUrl);
        }
        MessageRenderer renderer;
        try {
            renderer =
                    new MessageRenderer(subject, bodyTemplate, incidentTemplate, maxIncidentsPerMessage, parseZone());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage(), e);
        }
        channels = buildChannels(renderer);
        if (channels.isEmpty()) {
            throw invalid("no channel configured; set mailHost, webhookUrl, slackWebhookUrl or teamsWebhookUrl"
                    + " (or disable the plugin)");
        }
        initialized = true;
    }

    @Override
    public synchronized void postInit(ProcessEngineConfigurationImpl configuration) {
        IncidentDispatcher created =
                new IncidentDispatcher(channels, throttleMillis, maxBufferedIncidents, senderThreads, statistics);
        Map<String, IncidentHandler> handlers = new HashMap<>(configuration.getIncidentHandlers());
        for (String type : types) {
            IncidentHandler existing = handlers.get(type);
            if (existing == null) {
                created.close();
                throw invalid(
                        "no incident handler of type '" + type + "' is registered; known types: " + handlers.keySet());
            }
            handlers.put(
                    type,
                    new IncidentNotifyingHandler(
                            existing, cockpitBaseUrl, recipientVariable, ccVariable, created::submit, statistics));
        }
        configuration.setIncidentHandlers(handlers);
        dispatcher = created;
        LOG.info(
                "Incident notifier active for types {} over channels {} (throttle {} ms)",
                types,
                channels.stream().map(NotificationChannel::name).toList(),
                throttleMillis);
    }

    /** Delivers buffered notifications and stops the notifier's threads. Idempotent. */
    @Override
    public void close() {
        IncidentDispatcher current = dispatcher;
        if (current != null) {
            current.close();
        }
    }

    private List<NotificationChannel> buildChannels(MessageRenderer renderer) {
        List<NotificationChannel> result = new ArrayList<>();
        boolean anyMail = !isBlank(mailHost);
        if (!anyMail && (!isBlank(mailFrom) || !isBlank(mailTo) || !isBlank(mailUsername))) {
            throw invalid("mailFrom, mailTo or mailUsername is set but mailHost is not");
        }
        if (anyMail) {
            if (isBlank(mailFrom) || isBlank(mailTo)) {
                throw invalid("the email channel needs mailFrom and mailTo (the default recipients)");
            }
            if (mailPort < 1 || mailPort > 65535) {
                throw invalid("mailPort out of range: " + mailPort);
            }
            if (mailStartTlsRequired && !mailStartTls) {
                throw invalid("mailStartTlsRequired needs mailStartTls");
            }
            result.add(new EmailChannel(
                    new EmailSettings(
                            mailHost.strip(),
                            mailPort,
                            isBlank(mailUsername) ? null : mailUsername,
                            mailPassword,
                            mailFrom.strip(),
                            mailTo.strip(),
                            isBlank(mailCc) ? null : mailCc.strip(),
                            mailStartTls,
                            mailStartTlsRequired,
                            mailSsl,
                            mailTimeoutMillis),
                    renderer));
        }
        HttpJsonPoster poster = new HttpJsonPoster(Duration.ofMillis(webhookTimeoutMillis));
        if (!isBlank(webhookUrl)) {
            result.add(
                    WebhookChannel.generic(requireHttpUrl("webhookUrl", webhookUrl), webhookHeaders, poster, renderer));
        }
        if (!isBlank(slackWebhookUrl)) {
            result.add(WebhookChannel.slack(requireHttpUrl("slackWebhookUrl", slackWebhookUrl), poster, renderer));
        }
        if (!isBlank(teamsWebhookUrl)) {
            result.add(WebhookChannel.teams(requireHttpUrl("teamsWebhookUrl", teamsWebhookUrl), poster, renderer));
        }
        result.addAll(additionalChannels);
        return List.copyOf(result);
    }

    private List<String> parseTypes() {
        List<String> parsed = isBlank(incidentTypes)
                ? List.of()
                : Arrays.stream(incidentTypes.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();
        if (parsed.isEmpty()) {
            throw invalid("incidentTypes must name at least one incident type");
        }
        return parsed;
    }

    private ZoneId parseZone() {
        if (isBlank(timeZone)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            throw invalid("timeZone is not a valid zone id: " + timeZone, e);
        }
    }

    private URI requireHttpUrl(String property, String value) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw invalid(property + " must be an absolute http(s) URL");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw invalid(property + " is not a valid URL", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(PREFIX + message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(PREFIX + message, cause);
    }
}

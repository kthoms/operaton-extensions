/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Delivers digests as JSON over HTTP POST. One class serves the generic webhook and the Slack and
 * Microsoft Teams incoming-webhook formats; the format only differs in the payload.
 */
final class WebhookChannel implements NotificationChannel {

    static final String GENERIC = "webhook";
    static final String SLACK = "slack";
    static final String TEAMS = "teams";

    private final String name;
    private final URI uri;
    private final Map<String, String> headers;
    private final HttpJsonPoster poster;
    private final Function<IncidentDigest, String> payload;

    private WebhookChannel(
            String name,
            URI uri,
            Map<String, String> headers,
            HttpJsonPoster poster,
            Function<IncidentDigest, String> payload) {
        this.name = name;
        this.uri = uri;
        this.headers = Map.copyOf(headers);
        this.poster = poster;
        this.payload = payload;
    }

    /** Generic JSON webhook: structured incidents plus the rendered text. */
    static WebhookChannel generic(
            URI uri, Map<String, String> headers, HttpJsonPoster poster, MessageRenderer renderer) {
        return new WebhookChannel(GENERIC, uri, headers, poster, digest -> genericPayload(digest, renderer));
    }

    /** Slack incoming webhook: a single mrkdwn text. */
    static WebhookChannel slack(URI uri, HttpJsonPoster poster, MessageRenderer renderer) {
        return new WebhookChannel(SLACK, uri, Map.of(), poster, digest -> slackPayload(digest, renderer));
    }

    /** Microsoft Teams (Workflows / incoming webhook): a message carrying an Adaptive Card. */
    static WebhookChannel teams(URI uri, HttpJsonPoster poster, MessageRenderer renderer) {
        return new WebhookChannel(TEAMS, uri, Map.of(), poster, digest -> teamsPayload(digest, renderer));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void send(IncidentDigest digest) throws IOException {
        poster.post(uri, headers, payload.apply(digest));
    }

    static String genericPayload(IncidentDigest digest, MessageRenderer renderer) {
        List<Object> incidents = new ArrayList<>();
        for (IncidentInfo incident : digest.incidents()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", incident.id());
            entry.put("type", incident.type());
            entry.put("message", incident.message());
            entry.put("activityId", incident.activityId());
            entry.put("processDefinitionId", incident.processDefinitionId());
            entry.put("processDefinitionKey", incident.processDefinitionKey());
            entry.put("processDefinitionName", incident.processDefinitionName());
            entry.put("processInstanceId", incident.processInstanceId());
            entry.put("executionId", incident.executionId());
            entry.put("businessKey", incident.businessKey());
            entry.put("tenantId", incident.tenantId());
            entry.put("timestamp", incident.timestamp().toString());
            entry.put("cockpitUrl", incident.cockpitUrl());
            incidents.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("event", "operaton.incident.digest");
        root.put("processDefinitionKey", digest.processDefinitionKey());
        root.put("count", digest.total());
        root.put("suppressed", digest.suppressed());
        root.put("subject", renderer.subject(digest));
        root.put("text", renderer.body(digest, UnaryOperator.identity()));
        root.put("incidents", incidents);
        return Json.write(root);
    }

    static String slackPayload(IncidentDigest digest, MessageRenderer renderer) {
        String text = "*" + slackEscape(renderer.subject(digest)) + "*\n"
                + renderer.body(digest, WebhookChannel::slackEscape);
        return Json.write(Map.of("text", text));
    }

    private static String slackEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String teamsPayload(IncidentDigest digest, MessageRenderer renderer) {
        // Adaptive Card text blocks only break lines on a blank line
        String body = renderer.body(digest, UnaryOperator.identity())
                .replace("\r\n", "\n")
                .replace("\n", "\n\n");
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "TextBlock");
        title.put("size", "Medium");
        title.put("weight", "Bolder");
        title.put("wrap", true);
        title.put("text", renderer.subject(digest));
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "TextBlock");
        text.put("wrap", true);
        text.put("text", body);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", List.of(title, text));
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("contentUrl", null);
        attachment.put("content", card);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "message");
        root.put("attachments", List.of(attachment));
        return Json.write(root);
    }
}

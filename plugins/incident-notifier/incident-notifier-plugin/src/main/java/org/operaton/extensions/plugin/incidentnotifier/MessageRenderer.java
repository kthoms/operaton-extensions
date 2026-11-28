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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders subject and body of a notification from the configured templates.
 *
 * <p>The placeholder syntax ({@code @INCIDENTS}, {@code @ACTIVITY}, {@code @MESSAGE}, ...) is taken
 * over from the ported plugin and extended. Substituted values are never scanned again, so an
 * incident message that contains {@code @URL} stays literal. Unknown placeholders are rejected when
 * the renderer is created. Instances are immutable and thread-safe.
 */
final class MessageRenderer {

    static final String DEFAULT_SUBJECT = "[Operaton] @COUNT incident(s) in process @PROCESS_DEFINITION_KEY";

    static final String DEFAULT_BODY_TEMPLATE =
            "@COUNT incident(s) occurred in process @PROCESS_DEFINITION_NAME (@PROCESS_DEFINITION_KEY):\n\n@INCIDENTS";

    static final String DEFAULT_INCIDENT_TEMPLATE = "Incident type: @INCIDENT_TYPE\n"
            + "Message: @MESSAGE\n"
            + "Activity: @ACTIVITY\n"
            + "Process instance: @PROCESS_INSTANCE_ID\n"
            + "Business key: @BUSINESS_KEY\n"
            + "Time: @TIME\n"
            + "Cockpit: @URL\n";

    static final Set<String> SUBJECT_PLACEHOLDERS =
            Set.of("@COUNT", "@PROCESS_DEFINITION_KEY", "@PROCESS_DEFINITION_NAME");

    static final Set<String> BODY_PLACEHOLDERS =
            Set.of("@INCIDENTS", "@COUNT", "@PROCESS_DEFINITION_KEY", "@PROCESS_DEFINITION_NAME");

    static final Set<String> INCIDENT_PLACEHOLDERS = Set.of(
            "@INCIDENT_ID",
            "@INCIDENT_TYPE",
            "@MESSAGE",
            "@ACTIVITY",
            "@PROCESS_DEFINITION_ID",
            "@PROCESS_DEFINITION_KEY",
            "@PROCESS_DEFINITION_NAME",
            "@PROCESS_INSTANCE_ID",
            "@BUSINESS_KEY",
            "@TENANT_ID",
            "@TIME",
            "@URL");

    private static final Pattern PLACEHOLDER = Pattern.compile("@[A-Z][A-Z_]*[A-Z]");
    private static final String ABSENT = "-";

    private final String subjectTemplate;
    private final String bodyTemplate;
    private final String incidentTemplate;
    private final int maxIncidentsPerMessage;
    private final DateTimeFormatter timeFormat;

    MessageRenderer(
            String subjectTemplate,
            String bodyTemplate,
            String incidentTemplate,
            int maxIncidentsPerMessage,
            ZoneId zone) {
        this.subjectTemplate = validate("subject", subjectTemplate, SUBJECT_PLACEHOLDERS);
        this.bodyTemplate = validate("bodyTemplate", bodyTemplate, BODY_PLACEHOLDERS);
        this.incidentTemplate = validate("incidentTemplate", incidentTemplate, INCIDENT_PLACEHOLDERS);
        if (maxIncidentsPerMessage < 1) {
            throw new IllegalArgumentException("maxIncidentsPerMessage must be at least 1: " + maxIncidentsPerMessage);
        }
        this.maxIncidentsPerMessage = maxIncidentsPerMessage;
        this.timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(zone);
    }

    private static String validate(String name, String template, Set<String> allowed) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template '" + name + "' must not be blank");
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group())) {
                throw new IllegalArgumentException(
                        "template '" + name + "' contains the unknown placeholder " + matcher.group() + "; allowed: "
                                + allowed.stream().sorted().collect(Collectors.toList()));
            }
        }
        return template;
    }

    String subject(IncidentDigest digest) {
        return substitute(subjectTemplate, token -> digestValue(digest, token, null));
    }

    /** Renders the body; {@code escape} is applied to every substituted value (for example for Slack). */
    String body(IncidentDigest digest, UnaryOperator<String> escape) {
        List<IncidentInfo> shown =
                digest.incidents().subList(0, Math.min(digest.incidents().size(), maxIncidentsPerMessage));
        StringBuilder incidents = new StringBuilder();
        for (IncidentInfo incident : shown) {
            incidents.append(substitute(incidentTemplate, token -> escape.apply(incidentValue(incident, token))));
            if (incidents.charAt(incidents.length() - 1) != '\n') {
                incidents.append('\n');
            }
            incidents.append('\n');
        }
        int hidden = digest.total() - shown.size();
        if (hidden > 0) {
            incidents.append("... and ").append(hidden).append(" more incident(s) not shown.\n");
        }
        return substitute(bodyTemplate, token -> digestValue(digest, token, incidents.toString()));
    }

    private String digestValue(IncidentDigest digest, String token, String renderedIncidents) {
        return switch (token) {
            case "@INCIDENTS" -> renderedIncidents;
            case "@COUNT" -> Integer.toString(digest.total());
            case "@PROCESS_DEFINITION_KEY" -> digest.processDefinitionKey();
            case "@PROCESS_DEFINITION_NAME" ->
                orAbsent(digest.incidents().get(0).processDefinitionName());
            default -> throw new IllegalStateException("unhandled placeholder " + token);
        };
    }

    private String incidentValue(IncidentInfo incident, String token) {
        return switch (token) {
            case "@INCIDENT_ID" -> orAbsent(incident.id());
            case "@INCIDENT_TYPE" -> orAbsent(incident.type());
            case "@MESSAGE" -> orAbsent(incident.message());
            case "@ACTIVITY" -> orAbsent(incident.activityId());
            case "@PROCESS_DEFINITION_ID" -> orAbsent(incident.processDefinitionId());
            case "@PROCESS_DEFINITION_KEY" -> orAbsent(incident.processDefinitionKey());
            case "@PROCESS_DEFINITION_NAME" -> orAbsent(incident.processDefinitionName());
            case "@PROCESS_INSTANCE_ID" -> orAbsent(incident.processInstanceId());
            case "@BUSINESS_KEY" -> orAbsent(incident.businessKey());
            case "@TENANT_ID" -> orAbsent(incident.tenantId());
            case "@TIME" -> timeFormat.format(incident.timestamp());
            case "@URL" -> incident.cockpitUrl() == null ? "" : incident.cockpitUrl();
            default -> throw new IllegalStateException("unhandled placeholder " + token);
        };
    }

    private static String orAbsent(String value) {
        return value == null || value.isEmpty() ? ABSENT : value;
    }

    private static String substitute(String template, Function<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        int last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start()).append(values.apply(matcher.group()));
            last = matcher.end();
        }
        return out.append(template, last, template.length()).toString();
    }
}

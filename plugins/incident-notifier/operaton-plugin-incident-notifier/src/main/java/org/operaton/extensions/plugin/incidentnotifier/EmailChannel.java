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

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Sends digests by SMTP using Jakarta Mail (Eclipse Angus). Incidents are grouped by their effective
 * recipients (process variable or the configured defaults), one mail per group, as in the ported plugin.
 * Thread-safe: every delivery uses its own transport.
 */
final class EmailChannel implements NotificationChannel {

    static final String NAME = "email";

    private final EmailSettings settings;
    private final MessageRenderer renderer;
    private final Session session;

    EmailChannel(EmailSettings settings, MessageRenderer renderer) {
        this.settings = settings;
        this.renderer = renderer;
        Properties props = new Properties();
        String prefix = settings.ssl() ? "mail.smtps." : "mail.smtp.";
        props.put(prefix + "host", settings.host());
        props.put(prefix + "port", Integer.toString(settings.port()));
        props.put(prefix + "connectiontimeout", Integer.toString(settings.timeoutMillis()));
        props.put(prefix + "timeout", Integer.toString(settings.timeoutMillis()));
        props.put(prefix + "writetimeout", Integer.toString(settings.timeoutMillis()));
        props.put(prefix + "auth", Boolean.toString(settings.username() != null));
        if (!settings.ssl()) {
            props.put("mail.smtp.starttls.enable", Boolean.toString(settings.startTls()));
            props.put("mail.smtp.starttls.required", Boolean.toString(settings.startTlsRequired()));
        }
        this.session = Session.getInstance(props);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void send(IncidentDigest digest) throws MessagingException {
        Map<List<String>, List<IncidentInfo>> byRecipients = new LinkedHashMap<>();
        for (IncidentInfo incident : digest.incidents()) {
            String to = isBlank(incident.recipient()) ? settings.to() : incident.recipient();
            String cc = isBlank(incident.cc()) ? settings.cc() : incident.cc();
            List<String> key = new ArrayList<>(2);
            key.add(to);
            key.add(cc);
            byRecipients.computeIfAbsent(key, k -> new ArrayList<>()).add(incident);
        }
        List<MessagingException> failures = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<List<String>, List<IncidentInfo>> group : byRecipients.entrySet()) {
            // the suppressed count belongs to the digest, report it once
            IncidentDigest part = new IncidentDigest(
                    digest.processDefinitionKey(), group.getValue(), first ? digest.suppressed() : 0);
            first = false;
            try {
                deliver(part, group.getKey().get(0), group.getKey().get(1));
            } catch (MessagingException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            MessagingException failure = new MessagingException(
                    failures.size() + " of " + byRecipients.size() + " mails failed, first: "
                            + failures.get(0).getMessage(),
                    failures.get(0));
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private void deliver(IncidentDigest digest, String to, String cc) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(settings.from()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(Objects.requireNonNull(to)));
        if (!isBlank(cc)) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        message.setSentDate(new Date());
        message.setSubject(renderer.subject(digest), "UTF-8");
        message.setText(renderer.body(digest, UnaryOperator.identity()), "UTF-8");
        message.saveChanges();

        try (Transport transport = session.getTransport(settings.ssl() ? "smtps" : "smtp")) {
            transport.connect(settings.host(), settings.port(), settings.username(), settings.password());
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

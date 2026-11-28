/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.inbound;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.operaton.extensions.connector.mail.MailConnectorException;

/**
 * Maps incoming mails to a process: which mails it applies to (sender and subject patterns) and what happens to them
 * (start a process definition, or correlate a message). Immutable.
 *
 * <p>Patterns are regular expressions applied with {@code find()}; a pattern that is not set matches everything.
 * Routes are tried in order, the first match wins.
 *
 * What a matching mail does, in this order:
 *
 * <ol>
 *   <li>a business key was extracted and {@code messageName} is set: the message is correlated to the process instance
 *       with that business key (fails if there is none);
 *   <li>{@code processDefinitionKey} is set: a process instance is started (with the business key, if any);
 *   <li>otherwise the message is correlated to a message start event.
 * </ol>
 *
 * With both a process definition key and a message name a route therefore starts a process for a new conversation and
 * continues it when the subject carries the business key.
 *
 * The business key is group 1 of {@code businessKeyPattern} applied to the subject, for example {@code
 * \[(TICKET-\d+)\]}.
 */
public final class MailRoute {

    private final String name;
    private final Pattern fromPattern;
    private final Pattern subjectPattern;
    private final Pattern businessKeyPattern;
    private final String processDefinitionKey;
    private final String messageName;

    private MailRoute(Builder b) {
        this.name = b.name;
        this.fromPattern = compile(b.name, "fromPattern", b.fromPattern);
        this.subjectPattern = compile(b.name, "subjectPattern", b.subjectPattern);
        this.businessKeyPattern = compile(b.name, "businessKeyPattern", b.businessKeyPattern);
        this.processDefinitionKey = blankToNull(b.processDefinitionKey);
        this.messageName = blankToNull(b.messageName);
        if (name == null || name.isBlank()) {
            throw new MailConnectorException("Invalid mail route: 'name' is required");
        }
        if (processDefinitionKey == null && messageName == null) {
            throw new MailConnectorException(
                    "Invalid mail route '" + name + "': set processDefinitionKey or messageName");
        }
        if (businessKeyPattern != null && businessKeyPattern.matcher("").groupCount() < 1) {
            throw new MailConnectorException(
                    "Invalid mail route '" + name + "': businessKeyPattern needs a capturing group");
        }
    }

    private static Pattern compile(String route, String property, String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new MailConnectorException(
                    "Invalid mail route '" + route + "': " + property + " is not a valid regular expression", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String name() {
        return name;
    }

    public String processDefinitionKey() {
        return processDefinitionKey;
    }

    public String messageName() {
        return messageName;
    }

    /** Whether this route applies to a mail from {@code fromAddress} with {@code subject}. */
    public boolean matches(String fromAddress, String subject) {
        return find(fromPattern, fromAddress) && find(subjectPattern, subject);
    }

    private static boolean find(Pattern pattern, String value) {
        return pattern == null || (value != null && pattern.matcher(value).find());
    }

    /** The business key extracted from the subject, or {@code null}. */
    public String businessKey(String subject) {
        if (businessKeyPattern == null || subject == null) {
            return null;
        }
        Matcher matcher = businessKeyPattern.matcher(subject);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    public String toString() {
        return "MailRoute[" + name + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MailRoute other && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Builds a {@link MailRoute}; {@link #build()} validates. */
    public static final class Builder {

        private final String name;
        private String fromPattern;
        private String subjectPattern;
        private String businessKeyPattern;
        private String processDefinitionKey;
        private String messageName;

        private Builder(String name) {
            this.name = name;
        }

        public Builder fromPattern(String regex) {
            this.fromPattern = regex;
            return this;
        }

        public Builder subjectPattern(String regex) {
            this.subjectPattern = regex;
            return this;
        }

        public Builder businessKeyPattern(String regex) {
            this.businessKeyPattern = regex;
            return this;
        }

        public Builder processDefinitionKey(String key) {
            this.processDefinitionKey = key;
            return this;
        }

        public Builder messageName(String messageName) {
            this.messageName = messageName;
            return this;
        }

        public MailRoute build() {
            return new MailRoute(this);
        }
    }
}

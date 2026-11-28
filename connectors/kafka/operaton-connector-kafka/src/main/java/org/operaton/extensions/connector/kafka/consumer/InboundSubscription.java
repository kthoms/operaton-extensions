/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One inbound subscription: which topics are consumed and what each record does in the engine. Immutable and
 * validated on construction ({@link #builder(String)}): an invalid subscription never starts.
 *
 * <p>Delivery is at-least-once. The offset of a record is committed only after the engine call succeeded (or the
 * record was dead-lettered or skipped as poison). An engine failure (no matching process for a message, an
 * exception in the process, optimistic locking) is retried on the same record, in order within the partition, after
 * the retry backoff, up to {@code maxAttempts} (0 = without limit); then the record is poison. A record that cannot
 * be decoded or mapped is poison at once.
 */
public final class InboundSubscription {

    /** Consumer properties the extension sets itself. */
    private static final Set<String> RESERVED_PROPERTIES =
            Set.of("enable.auto.commit", "key.deserializer", "value.deserializer", "group.id");

    private final String name;
    private final List<String> topics;
    private final String groupId;
    private final InboundAction action;
    private final String processDefinitionKey;
    private final String messageName;
    private final String tenantId;
    private final ValueSource businessKey;
    private final Map<String, ValueSource> correlationKeys;
    private final Map<String, ValueSource> variables;
    private final ValueFormat valueFormat;
    private final int concurrency;
    private final PoisonHandling onPoison;
    private final String deadLetterTopic;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration pollTimeout;
    private final Map<String, String> consumerProperties;

    private InboundSubscription(Builder b) {
        this.name = requireText(b.name, "name");
        if (b.topics.isEmpty()) {
            throw invalid(name, "at least one topic is required");
        }
        b.topics.forEach(topic -> requireText(topic, "topic"));
        this.topics = List.copyOf(b.topics);
        this.groupId = b.groupId == null || b.groupId.isBlank() ? "operaton-" + name : b.groupId;
        this.action = Objects.requireNonNull(b.action, "action");
        this.tenantId = b.tenantId == null || b.tenantId.isBlank() ? null : b.tenantId;
        this.valueFormat = b.valueFormat;
        if (action == InboundAction.START_PROCESS) {
            if (b.processDefinitionKey == null || b.processDefinitionKey.isBlank()) {
                throw invalid(name, "action START_PROCESS requires processDefinitionKey");
            }
            if (b.messageName != null && !b.messageName.isBlank()) {
                throw invalid(name, "action START_PROCESS does not take a messageName");
            }
            if (!b.correlationKeys.isEmpty()) {
                throw invalid(name, "action START_PROCESS does not take correlation keys");
            }
        } else {
            if (b.messageName == null || b.messageName.isBlank()) {
                throw invalid(name, "action CORRELATE_MESSAGE requires messageName");
            }
            if (b.processDefinitionKey != null && !b.processDefinitionKey.isBlank()) {
                throw invalid(name, "action CORRELATE_MESSAGE does not take a processDefinitionKey");
            }
        }
        this.processDefinitionKey = blankToNull(b.processDefinitionKey);
        this.messageName = blankToNull(b.messageName);
        this.businessKey =
                b.businessKey == null || b.businessKey.isBlank() ? null : source(b.businessKey, "businessKey");
        this.correlationKeys = parseAll(b.correlationKeys, "correlation key");
        Map<String, String> variableSources = b.variables.isEmpty() ? Map.of("payload", "value") : b.variables;
        this.variables = parseAll(variableSources, "variable");
        if (valueFormat == ValueFormat.TEXT) {
            allSources().stream().filter(ValueSource::requiresJson).findFirst().ifPresent(s -> {
                throw invalid(name, "source '%s' needs JSON, but valueFormat is TEXT".formatted(s));
            });
        }
        if (b.concurrency < 1) {
            throw invalid(name, "concurrency must be at least 1");
        }
        this.concurrency = b.concurrency;
        this.onPoison = b.onPoison;
        this.deadLetterTopic = onPoison == PoisonHandling.DEAD_LETTER
                ? (b.deadLetterTopic == null || b.deadLetterTopic.isBlank() ? name + ".dlt" : b.deadLetterTopic)
                : null;
        if (b.maxAttempts < 0) {
            throw invalid(name, "maxAttempts must not be negative (0 = without limit)");
        }
        this.maxAttempts = b.maxAttempts;
        this.retryBackoff = positive(b.retryBackoff, "retryBackoff");
        this.pollTimeout = positive(b.pollTimeout, "pollTimeout");
        b.consumerProperties.keySet().forEach(key -> {
            if (RESERVED_PROPERTIES.contains(key)) {
                throw invalid(
                        name, "consumer property '%s' is set by the extension and cannot be overridden".formatted(key));
            }
        });
        this.consumerProperties = Map.copyOf(b.consumerProperties);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    private List<ValueSource> allSources() {
        List<ValueSource> all = new ArrayList<>(correlationKeys.values());
        all.addAll(variables.values());
        if (businessKey != null) {
            all.add(businessKey);
        }
        return all;
    }

    private Map<String, ValueSource> parseAll(Map<String, String> sources, String what) {
        Map<String, ValueSource> parsed = new LinkedHashMap<>();
        sources.forEach((key, expression) -> {
            requireText(key, what + " name");
            parsed.put(key, source(expression, what + " '" + key + "'"));
        });
        return Map.copyOf(parsed);
    }

    private ValueSource source(String expression, String what) {
        try {
            return ValueSource.parse(expression);
        } catch (IllegalArgumentException e) {
            throw invalid(name, what + ": " + e.getMessage());
        }
    }

    private Duration positive(Duration duration, String what) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw invalid(name, what + " must be positive");
        }
        return duration;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String requireText(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Kafka subscription: " + what + " must not be blank");
        }
        return text;
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException("Kafka subscription '%s': %s".formatted(name, reason));
    }

    public String name() {
        return name;
    }

    public List<String> topics() {
        return topics;
    }

    public String groupId() {
        return groupId;
    }

    public InboundAction action() {
        return action;
    }

    public String processDefinitionKey() {
        return processDefinitionKey;
    }

    public String messageName() {
        return messageName;
    }

    public String tenantId() {
        return tenantId;
    }

    /** The business key source, {@code null} when none. */
    public ValueSource businessKey() {
        return businessKey;
    }

    public Map<String, ValueSource> correlationKeys() {
        return correlationKeys;
    }

    public Map<String, ValueSource> variables() {
        return variables;
    }

    public ValueFormat valueFormat() {
        return valueFormat;
    }

    public int concurrency() {
        return concurrency;
    }

    public PoisonHandling onPoison() {
        return onPoison;
    }

    /** The dead-letter topic, {@code null} unless {@link #onPoison()} is {@code DEAD_LETTER}. */
    public String deadLetterTopic() {
        return deadLetterTopic;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public Duration pollTimeout() {
        return pollTimeout;
    }

    public Map<String, String> consumerProperties() {
        return consumerProperties;
    }

    /** Builder; not thread-safe. Defaults: JSON values, concurrency 1, dead-letter poison handling, unlimited retries every second. */
    public static final class Builder {
        private final String name;
        private final List<String> topics = new ArrayList<>();
        private String groupId;
        private InboundAction action;
        private String processDefinitionKey;
        private String messageName;
        private String tenantId;
        private String businessKey;
        private final Map<String, String> correlationKeys = new LinkedHashMap<>();
        private final Map<String, String> variables = new LinkedHashMap<>();
        private ValueFormat valueFormat = ValueFormat.JSON;
        private int concurrency = 1;
        private PoisonHandling onPoison = PoisonHandling.DEAD_LETTER;
        private String deadLetterTopic;
        private int maxAttempts;
        private Duration retryBackoff = Duration.ofSeconds(1);
        private Duration pollTimeout = Duration.ofMillis(500);
        private final Map<String, String> consumerProperties = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder topics(String... topics) {
            this.topics.addAll(List.of(topics));
            return this;
        }

        public Builder topics(List<String> topics) {
            this.topics.addAll(topics);
            return this;
        }

        /** Consumer group; default {@code operaton-<name>}. */
        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        /** Start a process instance of this definition key per record. */
        public Builder startProcess(String processDefinitionKey) {
            this.action = InboundAction.START_PROCESS;
            this.processDefinitionKey = processDefinitionKey;
            return this;
        }

        /** Correlate this message per record. */
        public Builder correlateMessage(String messageName) {
            this.action = InboundAction.CORRELATE_MESSAGE;
            this.messageName = messageName;
            return this;
        }

        public Builder action(InboundAction action) {
            this.action = action;
            return this;
        }

        public Builder processDefinitionKey(String processDefinitionKey) {
            this.processDefinitionKey = processDefinitionKey;
            return this;
        }

        public Builder messageName(String messageName) {
            this.messageName = messageName;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /** Source expression of the business key (start: the new instance's, correlate: the target's). */
        public Builder businessKey(String source) {
            this.businessKey = source;
            return this;
        }

        /** A correlation key: the target instance must have this variable equal to the source's value. */
        public Builder correlationKey(String variable, String source) {
            this.correlationKeys.put(variable, source);
            return this;
        }

        public Builder correlationKeys(Map<String, String> sources) {
            this.correlationKeys.putAll(sources);
            return this;
        }

        /** A process variable to set from a source. Without any, {@code payload} is set to the whole value. */
        public Builder variable(String variable, String source) {
            this.variables.put(variable, source);
            return this;
        }

        public Builder variables(Map<String, String> sources) {
            this.variables.putAll(sources);
            return this;
        }

        public Builder valueFormat(ValueFormat valueFormat) {
            this.valueFormat = Objects.requireNonNull(valueFormat, "valueFormat");
            return this;
        }

        /** Number of consumer threads (consumers of the group) for this subscription. */
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder onPoison(PoisonHandling onPoison) {
            this.onPoison = Objects.requireNonNull(onPoison, "onPoison");
            return this;
        }

        /** Dead-letter topic; default {@code <name>.dlt}. */
        public Builder deadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder retryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
            return this;
        }

        public Builder pollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
            return this;
        }

        /** A Kafka consumer property for this subscription's consumers (overrides the common ones). */
        public Builder consumerProperty(String name, String value) {
            this.consumerProperties.put(name, value);
            return this;
        }

        public Builder consumerProperties(Map<String, String> properties) {
            this.consumerProperties.putAll(properties);
            return this;
        }

        /**
         * @throws IllegalArgumentException when the subscription is invalid
         */
        public InboundSubscription build() {
            return new InboundSubscription(this);
        }
    }
}

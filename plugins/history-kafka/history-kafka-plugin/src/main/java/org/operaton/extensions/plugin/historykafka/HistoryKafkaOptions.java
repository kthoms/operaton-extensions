/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.operaton.extensions.plugin.historykafka.internal.HistoryEventCategories;

/**
 * Validated, immutable settings of a {@link KafkaHistoryEventHandler}.
 *
 * @param topic one topic for all events; {@code null} to use one topic per category
 * @param topicPrefix prefix of the per-category topic ({@code prefix + category})
 * @param topicMapping explicit topic per category ({@code process-instance}) or per category and
 *     event ({@code process-instance.end}); takes precedence over {@code topic} and the prefix
 * @param eventTypes categories ({@code process-instance}) or category and event
 *     ({@code process-instance.end}) to publish; empty publishes every history event
 * @param format wire format
 * @param source the CloudEvents {@code source} and the JSON envelope's {@code source}
 * @param failurePolicy reaction to a failed hand-over
 * @param sendMode asynchronous or synchronous hand-over
 * @param sendTimeout how long {@link SendMode#SYNC} waits for the broker, per transaction
 * @param includeVariableValues whether variable events carry the variable value
 */
public record HistoryKafkaOptions(
        String topic,
        String topicPrefix,
        Map<String, String> topicMapping,
        Set<String> eventTypes,
        PayloadFormat format,
        String source,
        FailurePolicy failurePolicy,
        SendMode sendMode,
        Duration sendTimeout,
        boolean includeVariableValues) {

    private static final Pattern TOPIC = Pattern.compile("[a-zA-Z0-9._-]{1,249}");
    private static final Pattern TOPIC_PREFIX = Pattern.compile("[a-zA-Z0-9._-]{0,100}");

    public HistoryKafkaOptions {
        if (topic != null && !isTopic(topic)) {
            throw new IllegalArgumentException(
                    "Invalid Kafka topic name '" + topic + "' (allowed: [a-zA-Z0-9._-], 1-249)");
        }
        if (topicPrefix == null || !TOPIC_PREFIX.matcher(topicPrefix).matches()) {
            throw new IllegalArgumentException(
                    "Invalid topic prefix '" + topicPrefix + "' (allowed: [a-zA-Z0-9._-], at most 100)");
        }
        Map<String, String> mapping = new TreeMap<>();
        if (topicMapping != null) {
            topicMapping.forEach((key, value) -> {
                checkCategoryOrEvent(key, "topic mapping key");
                if (value == null || !isTopic(value)) {
                    throw new IllegalArgumentException(
                            "Invalid Kafka topic name '" + value + "' for mapping '" + key + "'");
                }
                mapping.put(key, value);
            });
        }
        topicMapping = Map.copyOf(mapping);
        Set<String> filter = new TreeSet<>();
        if (eventTypes != null) {
            for (String entry : eventTypes) {
                checkCategoryOrEvent(entry, "event type");
                filter.add(entry);
            }
        }
        eventTypes = Set.copyOf(filter);
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (failurePolicy == null) {
            throw new IllegalArgumentException("failurePolicy must not be null");
        }
        if (sendMode == null) {
            throw new IllegalArgumentException("sendMode must not be null");
        }
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sendTimeout must be positive, was " + sendTimeout);
        }
    }

    private static boolean isTopic(String name) {
        return TOPIC.matcher(name).matches() && !name.equals(".") && !name.equals("..");
    }

    private static void checkCategoryOrEvent(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        String category = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
        String event = value.contains(".") ? value.substring(value.indexOf('.') + 1) : null;
        if (!HistoryEventCategories.KNOWN.contains(category) || (event != null && event.isBlank())) {
            throw new IllegalArgumentException("Unknown " + what + " '" + value + "'; known categories: "
                    + new TreeSet<>(HistoryEventCategories.KNOWN)
                    + " (optionally followed by .<event>, e.g. process-instance.end)");
        }
    }

    /** Options with the defaults: JSON, one topic per category, all events, fail loudly, async. */
    public static HistoryKafkaOptions defaults() {
        return new HistoryKafkaOptions(
                null,
                "operaton.history.",
                Map.of(),
                Set.of(),
                PayloadFormat.JSON,
                "urn:operaton:engine:default",
                FailurePolicy.FAIL,
                SendMode.ASYNC,
                Duration.ofSeconds(10),
                false);
    }

    /** Whether an event of this category and event name is published. */
    public boolean accepts(String category, String event) {
        return eventTypes.isEmpty()
                || eventTypes.contains(category)
                || (event != null && eventTypes.contains(category + "." + event));
    }

    /** The topic for an event: mapping (category.event, then category), the single topic, then prefix + category. */
    public String topicFor(String category, String event) {
        if (event != null) {
            String mapped = topicMapping.get(category + "." + event);
            if (mapped != null) {
                return mapped;
            }
        }
        String mapped = topicMapping.get(category);
        if (mapped != null) {
            return mapped;
        }
        return topic != null ? topic : topicPrefix + category;
    }
}

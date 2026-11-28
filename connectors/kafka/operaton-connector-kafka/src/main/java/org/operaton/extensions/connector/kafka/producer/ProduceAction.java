/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.extensions.connector.kafka.internal.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the parameters of a {@code kafka-produce} call (connector input parameters, or the input parameters of the
 * service task for the delegate) into a record and sends it. Stateless and thread-safe.
 *
 * <table>
 *   <caption>Parameters</caption>
 *   <tr><td>{@code topic}</td><td>required</td></tr>
 *   <tr><td>{@code key}</td><td>text (any value is converted, see {@link Values#toText(Object)})</td></tr>
 *   <tr><td>{@code value}</td><td>text; maps, lists and beans are written as JSON</td></tr>
 *   <tr><td>{@code headers}</td><td>a map of name to text</td></tr>
 *   <tr><td>{@code partition}</td><td>optional</td></tr>
 *   <tr><td>{@code timeoutMs}</td><td>acknowledgement timeout, default of the sender</td></tr>
 *   <tr><td>{@code deliveryMode}</td><td>{@code AFTER_COMMIT} (default) or {@code IMMEDIATE}</td></tr>
 *   <tr><td>{@code processHeaders}</td><td>add the {@code operaton-*} headers, default true</td></tr>
 *   <tr><td>{@code traceparent}, {@code tracestate}</td><td>W3C trace context; default: the process variables of the
 *       same name when present</td></tr>
 * </table>
 */
public final class ProduceAction {

    public static final String TOPIC = "topic";
    public static final String KEY = "key";
    public static final String VALUE = "value";
    public static final String HEADERS = "headers";
    public static final String PARTITION = "partition";
    public static final String TIMEOUT_MS = "timeoutMs";
    public static final String DELIVERY_MODE = "deliveryMode";
    public static final String PROCESS_HEADERS = "processHeaders";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    /** All parameter names, in documentation order. */
    public static final List<String> PARAMETERS = List.of(
            TOPIC, KEY, VALUE, HEADERS, PARTITION, TIMEOUT_MS, DELIVERY_MODE, PROCESS_HEADERS, TRACEPARENT, TRACESTATE);

    public static final String HEADER_PROCESS_INSTANCE_ID = "operaton-process-instance-id";
    public static final String HEADER_PROCESS_DEFINITION_ID = "operaton-process-definition-id";
    public static final String HEADER_BUSINESS_KEY = "operaton-business-key";
    public static final String HEADER_ACTIVITY_ID = "operaton-activity-id";

    private static final Logger LOG = LoggerFactory.getLogger(ProduceAction.class);
    private static final Pattern TRACEPARENT_FORMAT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private ProduceAction() {}

    /**
     * What happened.
     *
     * @param mode the delivery mode that was used
     * @param topic the topic
     * @param result the broker's answer, only for {@link DeliveryMode#IMMEDIATE}; {@code null} when the send is
     *     pending until the commit
     */
    public record Outcome(DeliveryMode mode, String topic, SendResult result) {}

    /**
     * Builds and sends (or schedules) the record.
     *
     * @param sender the sender
     * @param parameters the parameters, see the class documentation
     * @param execution the current execution for the process headers and trace context; {@code null} outside a process
     * @throws IllegalArgumentException for a missing or malformed parameter
     * @throws KafkaDeliveryException for a failed {@link DeliveryMode#IMMEDIATE} send
     */
    public static Outcome execute(
            KafkaRecordSender sender, Map<String, Object> parameters, DelegateExecution execution) {
        Objects.requireNonNull(sender, "sender");
        OutboundMessage message = buildMessage(parameters, execution);
        DeliveryMode mode = deliveryMode(parameters.get(DELIVERY_MODE));
        if (mode == DeliveryMode.IMMEDIATE) {
            return new Outcome(mode, message.topic(), sender.sendNow(message));
        }
        sender.sendAfterCommit(message);
        return new Outcome(mode, message.topic(), null);
    }

    /** Builds the record without sending it. */
    public static OutboundMessage buildMessage(Map<String, Object> parameters, DelegateExecution execution) {
        String topic = Values.toText(parameters.get(TOPIC));
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka produce: parameter '%s' is required".formatted(TOPIC));
        }
        OutboundMessage.Builder builder = OutboundMessage.builder(topic)
                .partition(integer(PARTITION, parameters.get(PARTITION)))
                .key(Values.utf8(Values.toText(parameters.get(KEY))))
                .value(Values.utf8(Values.toText(parameters.get(VALUE))));
        Integer timeoutMs = integer(TIMEOUT_MS, parameters.get(TIMEOUT_MS));
        if (timeoutMs != null) {
            builder.timeout(Duration.ofMillis(timeoutMs));
        }

        List<MessageHeader> headers = new ArrayList<>();
        if (execution != null && bool(PROCESS_HEADERS, parameters.get(PROCESS_HEADERS), true)) {
            addIfPresent(headers, HEADER_PROCESS_INSTANCE_ID, execution.getProcessInstanceId());
            addIfPresent(headers, HEADER_PROCESS_DEFINITION_ID, execution.getProcessDefinitionId());
            addIfPresent(headers, HEADER_BUSINESS_KEY, execution.getProcessBusinessKey());
            addIfPresent(headers, HEADER_ACTIVITY_ID, execution.getCurrentActivityId());
        }
        addTraceContext(headers, parameters, execution);
        addExplicitHeaders(headers, parameters.get(HEADERS));
        return builder.headers(headers).build();
    }

    private static void addTraceContext(
            List<MessageHeader> headers, Map<String, Object> parameters, DelegateExecution execution) {
        String traceparent = Values.toText(parameters.get(TRACEPARENT));
        if (traceparent != null && !TRACEPARENT_FORMAT.matcher(traceparent).matches()) {
            throw new IllegalArgumentException(
                    "Kafka produce: parameter '%s' is not a W3C traceparent (version-traceid-parentid-flags)"
                            .formatted(TRACEPARENT));
        }
        String tracestate = Values.toText(parameters.get(TRACESTATE));
        if (traceparent == null && execution != null) {
            Object variable = execution.getVariable(TRACEPARENT);
            String text = variable == null ? null : variable.toString();
            if (text != null && TRACEPARENT_FORMAT.matcher(text).matches()) {
                traceparent = text;
                if (tracestate == null && execution.getVariable(TRACESTATE) != null) {
                    tracestate = execution.getVariable(TRACESTATE).toString();
                }
            } else if (text != null) {
                LOG.warn("Process variable '{}' is not a W3C traceparent, not propagated", TRACEPARENT);
            }
        }
        addIfPresent(headers, TRACEPARENT, traceparent);
        if (traceparent != null) {
            addIfPresent(headers, TRACESTATE, tracestate);
        }
    }

    private static void addExplicitHeaders(List<MessageHeader> headers, Object explicit) {
        if (explicit == null) {
            return;
        }
        if (!(explicit instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "Kafka produce: parameter '%s' must be a map of header names to values, got %s"
                            .formatted(HEADERS, explicit.getClass().getName()));
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = Values.toText(entry.getKey());
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Kafka produce: header names must not be blank");
            }
            headers.removeIf(existing -> existing.name().equals(name));
            headers.add(MessageHeader.text(name, Values.toText(entry.getValue())));
        }
    }

    private static void addIfPresent(List<MessageHeader> headers, String name, String value) {
        if (value != null && !value.isEmpty()) {
            headers.add(MessageHeader.text(name, value));
        }
    }

    private static DeliveryMode deliveryMode(Object raw) {
        String text = Values.toText(raw);
        if (text == null || text.isBlank()) {
            return DeliveryMode.AFTER_COMMIT;
        }
        try {
            return DeliveryMode.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Kafka produce: parameter '%s' must be AFTER_COMMIT or IMMEDIATE, got '%s'"
                            .formatted(DELIVERY_MODE, text),
                    e);
        }
    }

    private static Integer integer(String name, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Kafka produce: parameter '%s' must be a number, got '%s'".formatted(name, raw), e);
        }
    }

    private static boolean bool(String name, Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = raw.toString().trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException(
                "Kafka produce: parameter '%s' must be true or false, got '%s'".formatted(name, raw));
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends records to Kafka. Owns one {@link KafkaProducer}.
 *
 * <p>Thread-safe: a single instance is shared by every thread of the engine (job executor, API callers), as the
 * Kafka producer itself is thread-safe. Keys and values are sent as raw bytes; the serializers cannot be configured.
 *
 * <p><b>Delivery semantics.</b> {@link #sendAfterCommit(OutboundMessage)} registers the send as a transaction
 * listener on {@code COMMITTED}: nothing is sent when the transaction rolls back, and a record is only sent for a
 * state that is durable in the engine database. The send is synchronous and waits for the acknowledgement up to the
 * timeout. The commit and the send are not atomic: if the JVM dies between them, or the broker is unavailable, the
 * record is lost (reported to the {@link DeliveryFailureHandler}); if the acknowledgement times out the record may
 * still arrive. Use an outbox table when that is not acceptable.
 */
public final class KafkaRecordSender implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRecordSender.class);

    /** Default wait for the broker's acknowledgement. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Producer<byte[], byte[]> producer;
    private final Duration defaultTimeout;
    private final DeliveryFailureHandler failureHandler;

    /**
     * Creates the producer.
     *
     * @param producerProperties Kafka producer properties ({@code bootstrap.servers} is required); serializers are
     *     forced to byte arrays
     * @param defaultTimeout the acknowledgement timeout for messages without their own
     * @param failureHandler called when an after-commit send fails
     * @throws IllegalArgumentException when {@code bootstrap.servers} is missing
     * @throws org.apache.kafka.common.KafkaException when the producer configuration is invalid
     */
    public KafkaRecordSender(
            Map<String, ?> producerProperties, Duration defaultTimeout, DeliveryFailureHandler failureHandler) {
        this(createProducer(producerProperties, defaultTimeout), defaultTimeout, failureHandler);
    }

    /** For tests and custom producers. */
    public KafkaRecordSender(
            Producer<byte[], byte[]> producer, Duration defaultTimeout, DeliveryFailureHandler failureHandler) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    private static Producer<byte[], byte[]> createProducer(Map<String, ?> properties, Duration timeout) {
        Objects.requireNonNull(properties, "producerProperties");
        Object bootstrap = properties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        if (bootstrap == null || bootstrap.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Kafka producer property '%s' is required".formatted(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        }
        Map<String, Object> config = new HashMap<>(properties);
        config.putIfAbsent(ProducerConfig.CLIENT_ID_CONFIG, "operaton-kafka-producer");
        config.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        config.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout.toMillis());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(config);
    }

    /**
     * Registers the send to run after the current engine transaction committed. Must be called from within an engine
     * command (a delegate, listener, connector).
     *
     * @throws IllegalStateException when there is no current engine command
     */
    public void sendAfterCommit(OutboundMessage message) {
        Objects.requireNonNull(message, "message");
        // Operaton 2.2 throws when there is no command, older versions return null
        CommandContext commandContext;
        try {
            commandContext = Context.getCommandContext();
        } catch (IllegalStateException e) {
            throw noCommand(message, e);
        }
        if (commandContext == null) {
            throw noCommand(message, null);
        }
        commandContext.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, ctx -> {
            try {
                sendNow(message);
            } catch (KafkaDeliveryException e) {
                failureHandler.onFailure(message, e);
            }
        });
    }

    private static IllegalStateException noCommand(OutboundMessage message, Throwable cause) {
        return new IllegalStateException(
                "Cannot send to topic '%s' after commit: no engine command is active".formatted(message.topic()),
                cause);
    }

    /**
     * Sends the record now and waits for the acknowledgement. Not transactional: a rollback of the current engine
     * transaction does not recall the record.
     *
     * @throws KafkaDeliveryException on a send error, interruption or timeout
     */
    public SendResult sendNow(OutboundMessage message) {
        Objects.requireNonNull(message, "message");
        Duration timeout = message.timeout() != null ? message.timeout() : defaultTimeout;
        List<Header> headers = new ArrayList<>(message.headers().size());
        for (MessageHeader header : message.headers()) {
            headers.add(new RecordHeader(header.name(), header.value()));
        }
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                message.topic(), message.partition(), null, message.key(), message.value(), headers);
        try {
            RecordMetadata metadata = producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            LOG.debug("Sent record to {}-{} at offset {}", metadata.topic(), metadata.partition(), metadata.offset());
            return new SendResult(metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaDeliveryException(
                    "Interrupted while sending a record to topic '%s'".formatted(message.topic()), e);
        } catch (TimeoutException e) {
            throw new KafkaDeliveryException(
                    "No acknowledgement for a record to topic '%s' within %s ms (it may still be delivered)"
                            .formatted(message.topic(), timeout.toMillis()),
                    e);
        } catch (ExecutionException e) {
            throw new KafkaDeliveryException(
                    "Sending a record to topic '%s' failed: %s"
                            .formatted(message.topic(), e.getCause().getMessage()),
                    e.getCause());
        } catch (RuntimeException e) {
            throw new KafkaDeliveryException(
                    "Sending a record to topic '%s' failed: %s".formatted(message.topic(), e.getMessage()), e);
        }
    }

    /** Flushes outstanding records and closes the producer. */
    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}

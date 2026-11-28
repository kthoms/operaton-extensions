/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.extensions.connector.kafka.internal.Values;
import org.operaton.extensions.connector.kafka.producer.KafkaDeliveryException;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.operaton.extensions.connector.kafka.producer.MessageHeader;
import org.operaton.extensions.connector.kafka.producer.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes the topics of one {@link InboundSubscription} and applies each record to the engine (start a process,
 * correlate a message).
 *
 * <p>Threading: {@code concurrency} worker threads, each with its own {@link KafkaConsumer} in the subscription's
 * consumer group; a record is handled by exactly one thread, records of a partition in order. The instance itself is
 * thread-safe for {@link #start()}, {@link #close()} and the status methods.
 *
 * <p>Offsets are committed manually and synchronously after each batch, covering only the records whose engine call
 * succeeded (or that were dead-lettered or skipped as poison). When the engine call for a record fails, the
 * partition is rewound to that record, paused for the retry backoff (polling continues, so the consumer stays in
 * the group) and the record is retried; the offsets of the records before it are committed, those after it are not
 * touched. After {@code maxAttempts} failures the record is handled as poison. This yields at-least-once processing:
 * a crash after the engine call and before the commit processes the record again.
 *
 * <p>Shutdown ({@link #close()}): the consumers are woken up, the record in progress finishes, its offset is
 * committed, the consumers leave the group.
 */
public final class KafkaProcessConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaProcessConsumer.class);

    /** Default time {@link #close()} waits for the workers. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

    /** Header names added to a dead-lettered record. */
    public static final String DLT_REASON = "x-dlt-reason";

    public static final String DLT_SUBSCRIPTION = "x-dlt-subscription";
    public static final String DLT_ORIGINAL_TOPIC = "x-dlt-original-topic";
    public static final String DLT_ORIGINAL_PARTITION = "x-dlt-original-partition";
    public static final String DLT_ORIGINAL_OFFSET = "x-dlt-original-offset";

    private final InboundSubscription subscription;
    private final Map<String, Object> consumerConfig;
    private final RecordHandler handler;
    private final KafkaRecordSender deadLetterSender;
    private final Duration shutdownTimeout;
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private boolean started;
    private boolean closed;

    private KafkaProcessConsumer(
            InboundSubscription subscription,
            Map<String, Object> consumerConfig,
            RecordHandler handler,
            KafkaRecordSender deadLetterSender,
            Duration shutdownTimeout) {
        this.subscription = subscription;
        this.consumerConfig = consumerConfig;
        this.handler = handler;
        this.deadLetterSender = deadLetterSender;
        this.shutdownTimeout = shutdownTimeout;
    }

    /**
     * Creates the consumer; nothing is started yet.
     *
     * @param subscription the subscription
     * @param consumerProperties the common Kafka consumer properties; {@code bootstrap.servers} is required, the
     *     subscription's own consumer properties override them; {@code enable.auto.commit}, the deserializers and
     *     {@code group.id} are set by the extension and rejected here
     * @param runtimeService the engine's runtime service
     * @param deadLetterSender required when the subscription dead-letters poison records
     * @param shutdownTimeout how long {@link #close()} waits for the workers
     * @throws IllegalArgumentException for an invalid configuration
     */
    public static KafkaProcessConsumer create(
            InboundSubscription subscription,
            Map<String, ?> consumerProperties,
            RuntimeService runtimeService,
            KafkaRecordSender deadLetterSender,
            Duration shutdownTimeout) {
        Objects.requireNonNull(subscription, "subscription");
        Objects.requireNonNull(runtimeService, "runtimeService");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (subscription.onPoison() == PoisonHandling.DEAD_LETTER && deadLetterSender == null) {
            throw new IllegalArgumentException(
                    "Kafka subscription '%s' dead-letters poison records and needs a producer"
                            .formatted(subscription.name()));
        }
        Map<String, Object> config = new HashMap<>(consumerProperties);
        for (String reserved : List.of(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ConsumerConfig.GROUP_ID_CONFIG)) {
            if (config.containsKey(reserved)) {
                throw new IllegalArgumentException(
                        "Kafka consumer property '%s' is set by the extension and cannot be configured"
                                .formatted(reserved));
            }
        }
        Object bootstrap = config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        if (bootstrap == null || bootstrap.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Kafka consumer property '%s' is required".formatted(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        }
        config.putAll(subscription.consumerProperties());
        config.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, subscription.groupId());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaProcessConsumer(
                subscription,
                config,
                new RecordHandler(subscription, runtimeService),
                deadLetterSender,
                shutdownTimeout);
    }

    public InboundSubscription subscription() {
        return subscription;
    }

    /**
     * Creates the Kafka consumers and starts the worker threads.
     *
     * @throws IllegalStateException when already started or closed
     * @throws org.apache.kafka.common.KafkaException when the consumer configuration is invalid
     */
    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException(
                    "Kafka subscription '%s' is already started or closed".formatted(subscription.name()));
        }
        try {
            for (int i = 0; i < subscription.concurrency(); i++) {
                Map<String, Object> config = new HashMap<>(consumerConfig);
                config.putIfAbsent(ConsumerConfig.CLIENT_ID_CONFIG, "%s-%d".formatted(subscription.name(), i));
                workers.add(new Worker(i, new KafkaConsumer<>(config)));
            }
        } catch (RuntimeException e) {
            workers.forEach(Worker::discard);
            workers.clear();
            throw e;
        }
        started = true;
        workers.forEach(Worker::start);
        LOG.info(
                "Kafka subscription '{}' started: topics {}, group '{}', {} consumer(s)",
                subscription.name(),
                subscription.topics(),
                subscription.groupId(),
                workers.size());
    }

    /** Whether at least one worker is alive. A worker stops by itself only on an unrecoverable error. */
    public boolean isRunning() {
        return workers.stream().anyMatch(w -> w.thread.isAlive());
    }

    /** The unrecoverable errors that stopped workers (also logged at ERROR). */
    public List<Throwable> failures() {
        List<Throwable> failures = new ArrayList<>();
        for (Worker worker : workers) {
            if (worker.failure != null) {
                failures.add(worker.failure);
            }
        }
        return failures;
    }

    /** Stops the workers gracefully, see the class documentation. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        workers.forEach(Worker::requestStop);
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        for (Worker worker : workers) {
            try {
                long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                worker.thread.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (worker.thread.isAlive()) {
                LOG.warn(
                        "Kafka subscription '{}': consumer {} did not stop within {}, interrupting",
                        subscription.name(),
                        worker.index,
                        shutdownTimeout);
                worker.thread.interrupt();
            }
        }
        if (started) {
            LOG.info("Kafka subscription '{}' stopped", subscription.name());
        }
    }

    private enum Outcome {
        DONE,
        RETRY
    }

    private record Attempt(long offset, int count) {}

    /** One consumer thread; every field except the volatiles is touched only by that thread. */
    private final class Worker implements Runnable, ConsumerRebalanceListener {
        final int index;
        final KafkaConsumer<byte[], byte[]> consumer;
        final Thread thread;
        volatile boolean stopping;
        volatile Throwable failure;
        final Map<TopicPartition, Attempt> attempts = new HashMap<>();
        final Map<TopicPartition, Long> resumeAt = new HashMap<>();

        Worker(int index, KafkaConsumer<byte[], byte[]> consumer) {
            this.index = index;
            this.consumer = consumer;
            this.thread = new Thread(this, "operaton-kafka-" + subscription.name() + "-" + index);
        }

        void start() {
            thread.start();
        }

        void discard() {
            consumer.close(Duration.ZERO);
        }

        void requestStop() {
            stopping = true;
            consumer.wakeup();
        }

        @Override
        public void run() {
            try {
                consumer.subscribe(subscription.topics(), this);
                while (!stopping) {
                    ConsumerRecords<byte[], byte[]> records;
                    try {
                        records = consumer.poll(subscription.pollTimeout());
                    } catch (WakeupException e) {
                        break;
                    }
                    resumeDuePartitions();
                    if (!records.isEmpty()) {
                        processBatch(records);
                    }
                }
            } catch (Throwable t) {
                failure = t;
                LOG.error(
                        "Kafka subscription '{}': consumer {} stopped after an unrecoverable error",
                        subscription.name(),
                        index,
                        t);
            } finally {
                try {
                    consumer.close(Duration.ofSeconds(5));
                } catch (RuntimeException e) {
                    LOG.warn("Kafka subscription '{}': closing consumer {} failed", subscription.name(), index, e);
                }
            }
        }

        private void processBatch(ConsumerRecords<byte[], byte[]> records) {
            Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
            for (TopicPartition partition : records.partitions()) {
                for (ConsumerRecord<byte[], byte[]> record : records.records(partition)) {
                    if (stopping) {
                        break;
                    }
                    if (process(partition, record) == Outcome.RETRY) {
                        consumer.seek(partition, record.offset());
                        consumer.pause(List.of(partition));
                        resumeAt.put(
                                partition,
                                System.nanoTime() + subscription.retryBackoff().toNanos());
                        break;
                    }
                    toCommit.put(partition, new OffsetAndMetadata(record.offset() + 1));
                }
            }
            commit(toCommit);
        }

        private void resumeDuePartitions() {
            if (resumeAt.isEmpty()) {
                return;
            }
            long now = System.nanoTime();
            for (Iterator<Map.Entry<TopicPartition, Long>> it =
                            resumeAt.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<TopicPartition, Long> entry = it.next();
                if (now - entry.getValue() >= 0) {
                    consumer.resume(List.of(entry.getKey()));
                    it.remove();
                }
            }
        }

        private Outcome process(TopicPartition partition, ConsumerRecord<byte[], byte[]> record) {
            String coordinates = record.topic() + "-" + record.partition() + "@" + record.offset();
            try {
                handler.handle(decode(record));
                attempts.remove(partition);
                return Outcome.DONE;
            } catch (MappingException | DecodingException e) {
                return poison(partition, record, e);
            } catch (RuntimeException e) {
                Attempt previous = attempts.get(partition);
                int count = previous != null && previous.offset() == record.offset() ? previous.count() + 1 : 1;
                if (subscription.maxAttempts() > 0 && count >= subscription.maxAttempts()) {
                    return poison(partition, record, e);
                }
                attempts.put(partition, new Attempt(record.offset(), count));
                LOG.warn(
                        "Kafka subscription '{}': record {} failed in the engine (attempt {}), the offset is not committed, retrying in {}: {}",
                        subscription.name(),
                        coordinates,
                        count,
                        subscription.retryBackoff(),
                        e.toString());
                return Outcome.RETRY;
            }
        }

        private InboundRecord decode(ConsumerRecord<byte[], byte[]> record) {
            try {
                List<MessageHeader> headers = new ArrayList<>();
                for (Header header : record.headers()) {
                    headers.add(new MessageHeader(header.key(), header.value()));
                }
                return new InboundRecord(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.timestamp(),
                        Values.decodeUtf8(record.key()),
                        Values.decodeUtf8(record.value()),
                        headers);
            } catch (IllegalArgumentException e) {
                throw new DecodingException(
                        "record %s-%d@%d: key or value is not valid UTF-8 text"
                                .formatted(record.topic(), record.partition(), record.offset()),
                        e);
            }
        }

        private Outcome poison(
                TopicPartition partition, ConsumerRecord<byte[], byte[]> record, RuntimeException reason) {
            attempts.remove(partition);
            String coordinates = record.topic() + "-" + record.partition() + "@" + record.offset();
            if (subscription.onPoison() == PoisonHandling.SKIP) {
                LOG.error(
                        "Kafka subscription '{}': record {} skipped as poison: {}",
                        subscription.name(),
                        coordinates,
                        reason.toString());
                return Outcome.DONE;
            }
            OutboundMessage.Builder dead = OutboundMessage.builder(subscription.deadLetterTopic())
                    .key(record.key())
                    .value(record.value());
            for (Header header : record.headers()) {
                dead.header(new MessageHeader(header.key(), header.value()));
            }
            dead.header(MessageHeader.text(DLT_REASON, reason.toString()))
                    .header(MessageHeader.text(DLT_SUBSCRIPTION, subscription.name()))
                    .header(MessageHeader.text(DLT_ORIGINAL_TOPIC, record.topic()))
                    .header(MessageHeader.text(DLT_ORIGINAL_PARTITION, String.valueOf(record.partition())))
                    .header(MessageHeader.text(DLT_ORIGINAL_OFFSET, String.valueOf(record.offset())));
            try {
                deadLetterSender.sendNow(dead.build());
            } catch (KafkaDeliveryException e) {
                LOG.error(
                        "Kafka subscription '{}': record {} is poison ({}) but could not be sent to the dead-letter topic '{}'; the offset is not committed, retrying in {}",
                        subscription.name(),
                        coordinates,
                        reason.toString(),
                        subscription.deadLetterTopic(),
                        subscription.retryBackoff(),
                        e);
                return Outcome.RETRY;
            }
            LOG.error(
                    "Kafka subscription '{}': record {} dead-lettered to '{}': {}",
                    subscription.name(),
                    coordinates,
                    subscription.deadLetterTopic(),
                    reason.toString());
            return Outcome.DONE;
        }

        private void commit(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (offsets.isEmpty()) {
                return;
            }
            try {
                try {
                    consumer.commitSync(offsets);
                } catch (WakeupException e) {
                    // shutdown was requested while the record was being processed: commit it anyway
                    consumer.commitSync(offsets);
                }
            } catch (CommitFailedException e) {
                LOG.warn(
                        "Kafka subscription '{}': offsets {} not committed (partitions were reassigned); the records will be processed again",
                        subscription.name(),
                        offsets,
                        e);
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            forget(partitions);
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            forget(partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            // nothing to do: the committed offsets decide where reading continues
        }

        private void forget(Collection<TopicPartition> partitions) {
            partitions.forEach(partition -> {
                attempts.remove(partition);
                resumeAt.remove(partition);
            });
        }
    }

    /** The record's key or value is not text. */
    private static final class DecodingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

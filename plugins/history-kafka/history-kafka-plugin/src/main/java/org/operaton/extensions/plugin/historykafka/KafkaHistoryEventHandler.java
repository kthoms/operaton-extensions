/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.extensions.plugin.historykafka.internal.EventSerializer;
import org.operaton.extensions.plugin.historykafka.internal.OutgoingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes history events to Kafka once the engine transaction that produced them has committed.
 *
 * <p>Events are serialized and buffered per command context while the transaction runs. A
 * transaction listener on {@code COMMITTED} hands the buffer to the Kafka producer; one on {@code
 * ROLLED_BACK} drops it, so nothing is ever published for a rolled-back transaction. Events are
 * published in the order the engine produced them; records with the same key (the process instance
 * id) land on the same partition and keep that order.
 *
 * <p><b>Delivery guarantee.</b> The Kafka send happens after the database commit and cannot take
 * part in it. If the JVM dies, or the broker is unreachable, between commit and send, the events
 * of that transaction are lost (at-most-once). {@link SendMode#SYNC} plus {@link
 * FailurePolicy#FAIL} reports such a failure to the caller, who can retry the business operation
 * (at-least-once, with possible duplicates: every message carries a unique id for de-duplication).
 *
 * <p>Thread-safe: instances are shared by all engine threads. The only shared mutable state is the
 * (synchronized, weakly keyed) buffer registry, the counters and the thread-safe Kafka producer.
 * Each buffer is only touched by the thread running its command.
 */
public final class KafkaHistoryEventHandler implements HistoryEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaHistoryEventHandler.class);

    private final Producer<String, String> producer;
    private final HistoryKafkaOptions options;
    private final EventSerializer serializer;
    private final Map<CommandContext, List<OutgoingEvent>> buffers = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();

    public KafkaHistoryEventHandler(Producer<String, String> producer, HistoryKafkaOptions options) {
        this.producer = java.util.Objects.requireNonNull(producer, "producer");
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.serializer = new EventSerializer(options);
    }

    @Override
    public void handleEvent(HistoryEvent historyEvent) {
        handleEvents(List.of(historyEvent));
    }

    @Override
    public void handleEvents(List<HistoryEvent> historyEvents) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            throw new HistoryKafkaException(
                    "History events must be handled inside an engine command; there is no command context on this thread");
        }
        Instant now = Instant.now();
        List<OutgoingEvent> accepted = new ArrayList<>(historyEvents.size());
        for (HistoryEvent historyEvent : historyEvents) {
            OutgoingEvent outgoing = serializer.serialize(historyEvent, now);
            if (outgoing != null) {
                accepted.add(outgoing);
            }
        }
        if (!accepted.isEmpty()) {
            buffers.computeIfAbsent(commandContext, this::newBuffer).addAll(accepted);
        }
    }

    private List<OutgoingEvent> newBuffer(CommandContext commandContext) {
        List<OutgoingEvent> buffer = new ArrayList<>();
        commandContext.getTransactionContext().addTransactionListener(TransactionState.COMMITTED, context -> {
            buffers.remove(context);
            publish(buffer);
        });
        commandContext.getTransactionContext().addTransactionListener(TransactionState.ROLLED_BACK, context -> {
            buffers.remove(context);
            discarded.addAndGet(buffer.size());
            LOG.debug("Transaction rolled back, discarding {} buffered history event(s)", buffer.size());
        });
        return buffer;
    }

    private void publish(List<OutgoingEvent> events) {
        List<Failure> failures = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        for (OutgoingEvent event : events) {
            try {
                if (options.sendMode() == SendMode.SYNC) {
                    pending.add(new Pending(event, producer.send(event.toRecord())));
                } else {
                    producer.send(event.toRecord(), (metadata, exception) -> completed(event, exception));
                }
            } catch (RuntimeException e) {
                failures.add(new Failure(event, e));
            }
        }
        awaitAcknowledgements(pending, failures);
        reportFailures(events.size(), failures);
    }

    private void awaitAcknowledgements(List<Pending> pending, List<Failure> failures) {
        long deadline = System.nanoTime() + options.sendTimeout().toNanos();
        boolean interrupted = false;
        for (Pending item : pending) {
            if (interrupted) {
                failures.add(
                        new Failure(item.event(), new InterruptedException("interrupted while waiting for Kafka")));
                continue;
            }
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                item.future().get(remaining, TimeUnit.NANOSECONDS);
                published.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                failures.add(new Failure(item.event(), e));
            } catch (ExecutionException e) {
                failures.add(new Failure(item.event(), e.getCause() != null ? e.getCause() : e));
            } catch (TimeoutException e) {
                failures.add(new Failure(
                        item.event(),
                        new TimeoutException("no acknowledgement from Kafka within "
                                + options.sendTimeout().toMillis() + " ms")));
            }
        }
    }

    private void completed(OutgoingEvent event, Exception exception) {
        if (exception == null) {
            published.incrementAndGet();
        } else {
            failed.incrementAndGet();
            LOG.error(
                    "Kafka did not accept history event after commit ({}); the engine transaction is already committed",
                    event.describe(),
                    exception);
        }
    }

    private void reportFailures(int total, List<Failure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        failed.addAndGet(failures.size());
        for (Failure failure : failures) {
            LOG.error(
                    "Cannot publish history event after commit ({}); the engine transaction is already committed",
                    failure.event().describe(),
                    failure.cause());
        }
        if (options.failurePolicy() == FailurePolicy.FAIL) {
            Failure first = failures.get(0);
            HistoryKafkaException exception = new HistoryKafkaException(
                    "Publishing " + failures.size() + " of " + total
                            + " history event(s) to Kafka failed after the engine"
                            + " transaction committed (the transaction is NOT rolled back). First failure: "
                            + first.event().describe() + ": " + first.cause(),
                    first.cause());
            failures.stream().skip(1).forEach(f -> exception.addSuppressed(f.cause()));
            throw exception;
        }
    }

    /** Events acknowledged by Kafka (synchronous mode: when the broker acknowledged them; asynchronous: in the callback). */
    public long getPublishedCount() {
        return published.get();
    }

    /** Events that could not be handed over to Kafka or that Kafka rejected. */
    public long getFailedCount() {
        return failed.get();
    }

    /** Buffered events dropped because their transaction rolled back. */
    public long getDiscardedCount() {
        return discarded.get();
    }

    /** The settings this handler was created with. */
    public HistoryKafkaOptions getOptions() {
        return options;
    }

    private record Pending(OutgoingEvent event, Future<RecordMetadata> future) {}

    private record Failure(OutgoingEvent event, Throwable cause) {}
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects history documents in a bounded queue and writes them with a single background thread, one bulk
 * request per {@code batchSize} documents or per {@code flushInterval}, whichever comes first. One writer
 * keeps the documents in the order the engine committed them. Thread-safe: any thread may submit.
 */
public final class BulkProcessor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BulkProcessor.class);
    private static final Object STOP = new Object();

    private record FlushMarker(CompletableFuture<Void> done) {}

    private final BulkSender sender;
    private final HistoryOpenSearchOptions options;
    private final Consumer<Outcome> listener;
    private final BlockingQueue<Object> queue;
    private final Thread worker;
    private volatile boolean closed;

    /**
     * Creates the processor and starts its thread.
     *
     * @param sender writes a batch
     * @param options batch size, flush interval, queue capacity and timeouts
     * @param listener receives the outcome of every batch, on the writer thread
     * @param threadName name of the writer thread
     */
    public BulkProcessor(
            BulkSender sender, HistoryOpenSearchOptions options, Consumer<Outcome> listener, String threadName) {
        this.sender = sender;
        this.options = options;
        this.listener = listener;
        this.queue = new LinkedBlockingQueue<>(options.queueCapacity());
        this.worker = new Thread(this::run, threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queues the documents; waits up to {@code sendTimeout} per document for a free slot.
     *
     * @throws HistoryOpenSearchException when the processor is closed or the queue stays full; the
     *     message tells how many documents were not queued
     */
    public void submit(List<HistoryDocument> documents) {
        for (int i = 0; i < documents.size(); i++) {
            if (closed) {
                throw new HistoryOpenSearchException("history-opensearch: the plugin is closed; "
                        + (documents.size() - i) + " history document(s) were not queued");
            }
            try {
                if (!queue.offer(documents.get(i), options.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new HistoryOpenSearchException("history-opensearch: the queue of " + options.queueCapacity()
                            + " documents stayed full for "
                            + options.sendTimeout().toMillis() + " ms (OpenSearch too slow?); "
                            + (documents.size() - i) + " history document(s) were not queued");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HistoryOpenSearchException(
                        "history-opensearch: interrupted while queueing; " + (documents.size() - i)
                                + " history document(s) were not queued",
                        e);
            }
        }
    }

    /**
     * Waits until everything queued before this call has been written (or has failed).
     *
     * @throws HistoryOpenSearchException on timeout or interruption
     */
    public void flush(Duration timeout) {
        FlushMarker marker = new FlushMarker(new CompletableFuture<>());
        try {
            if (!queue.offer(marker, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new HistoryOpenSearchException("history-opensearch: flush timed out, the queue is full");
            }
            marker.done().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HistoryOpenSearchException("history-opensearch: interrupted while flushing", e);
        } catch (TimeoutException e) {
            throw new HistoryOpenSearchException(
                    "history-opensearch: flush did not finish within " + timeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            throw new HistoryOpenSearchException("history-opensearch: flush failed", e.getCause());
        }
    }

    /** Writes what is queued (waiting up to {@code sendTimeout}), then stops the thread. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        long timeout = options.sendTimeout().toMillis();
        try {
            if (!queue.offer(STOP, timeout, TimeUnit.MILLISECONDS)) {
                LOG.error(
                        "history-opensearch: the queue did not drain within {} ms on close; unwritten documents are lost",
                        timeout);
                worker.interrupt();
            }
            worker.join(timeout);
            if (worker.isAlive()) {
                LOG.error(
                        "history-opensearch: the writer did not finish within {} ms on close; unwritten documents are lost",
                        timeout);
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        }
    }

    private void run() {
        boolean stop = false;
        while (!stop) {
            List<HistoryDocument> batch = new ArrayList<>();
            List<FlushMarker> markers = new ArrayList<>();
            try {
                Object head = queue.take();
                stop = absorb(head, batch, markers);
                long deadline = System.nanoTime() + options.flushInterval().toNanos();
                while (!stop && markers.isEmpty() && batch.size() < options.batchSize()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    Object next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    stop = absorb(next, batch, markers);
                }
            } catch (InterruptedException e) {
                LOG.warn(
                        "history-opensearch: writer interrupted; {} queued document(s) in hand are written, the rest is dropped",
                        batch.size());
                stop = true;
            }
            write(batch);
            markers.forEach(marker -> marker.done().complete(null));
        }
        // anything still queued after STOP (documents submitted racing with close) is dropped loudly
        List<Object> rest = new ArrayList<>();
        queue.drainTo(rest);
        long lost = rest.stream().filter(HistoryDocument.class::isInstance).count();
        if (lost > 0) {
            LOG.error(
                    "history-opensearch: {} history document(s) were queued while the plugin was closing and are lost",
                    lost);
            listener.accept(new Outcome(
                    0,
                    List.of(new Outcome.Failure(
                            lost + " document(s) queued while closing",
                            new HistoryOpenSearchException("plugin closed")))));
        }
        rest.stream()
                .filter(FlushMarker.class::isInstance)
                .forEach(m -> ((FlushMarker) m)
                        .done()
                        .completeExceptionally(new HistoryOpenSearchException("plugin closed")));
    }

    private static boolean absorb(Object element, List<HistoryDocument> batch, List<FlushMarker> markers) {
        if (element == STOP) {
            return true;
        }
        if (element instanceof FlushMarker marker) {
            markers.add(marker);
        } else {
            batch.add((HistoryDocument) element);
        }
        return false;
    }

    private void write(List<HistoryDocument> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Outcome outcome;
        try {
            outcome = sender.send(batch);
        } catch (RuntimeException e) {
            LOG.error("history-opensearch: unexpected error while writing {} document(s)", batch.size(), e);
            List<Outcome.Failure> failures = new ArrayList<>();
            batch.forEach(d -> failures.add(new Outcome.Failure(d.describe(), e)));
            outcome = new Outcome(0, failures);
        }
        listener.accept(outcome);
    }
}

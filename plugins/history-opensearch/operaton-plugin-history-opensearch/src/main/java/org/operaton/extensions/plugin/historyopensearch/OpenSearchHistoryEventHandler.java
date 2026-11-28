/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.extensions.plugin.historyopensearch.internal.BulkProcessor;
import org.operaton.extensions.plugin.historyopensearch.internal.BulkSender;
import org.operaton.extensions.plugin.historyopensearch.internal.DocumentMapper;
import org.operaton.extensions.plugin.historyopensearch.internal.HistoryDocument;
import org.operaton.extensions.plugin.historyopensearch.internal.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes history events into OpenSearch once the engine transaction that produced them has committed.
 *
 * <p>Each event becomes a change to a <em>current-state document</em> (one per process instance,
 * activity instance, task, variable and incident). The changes are buffered per command context while
 * the transaction runs. A transaction listener on {@code COMMITTED} hands the buffer over for writing;
 * one on {@code ROLLED_BACK} drops it, so nothing is ever indexed for a rolled-back transaction. In
 * {@link SendMode#ASYNC} the documents are queued and a background thread writes them in bulk requests;
 * in {@link SendMode#SYNC} they are written, with retries, before the engine command returns.
 *
 * <p><b>Delivery guarantee.</b> The write happens after the database commit and cannot take part in it.
 * If the JVM dies, or OpenSearch stays unreachable past the retries, the documents of that transaction
 * are lost (at-most-once). {@link SendMode#SYNC} plus {@link FailurePolicy#FAIL} reports a failed write to
 * the caller, who can retry the business operation; indexing is idempotent (upsert by id), so a retry
 * does not duplicate documents.
 *
 * <p>Thread-safe: instances are shared by all engine threads. The shared mutable state is the
 * (synchronized, weakly keyed) buffer registry, the counters, and the thread-safe writer. Each buffer
 * is only touched by the thread running its command.
 */
public final class OpenSearchHistoryEventHandler implements HistoryEventHandler, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchHistoryEventHandler.class);

    private final HistoryOpenSearchOptions options;
    private final BulkSender sender;
    private final BulkProcessor processor;
    private final DocumentMapper mapper;
    private final Map<CommandContext, List<HistoryDocument>> buffers = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicLong indexed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();

    OpenSearchHistoryEventHandler(HistoryOpenSearchOptions options, ObjectMapper json, String engineName) {
        this.options = Objects.requireNonNull(options, "options");
        this.sender = new BulkSender(options, Objects.requireNonNull(json, "json"));
        this.mapper = new DocumentMapper(options);
        this.processor = options.sendMode() == SendMode.ASYNC
                ? new BulkProcessor(sender, options, this::completed, "operaton-history-opensearch-" + engineName)
                : null;
    }

    /**
     * Installs the index templates (when managed). Called by the plugin once the engine is built; a
     * failure is thrown when the failure policy is {@link FailurePolicy#FAIL}, otherwise logged, and the
     * installation is tried again before the next write.
     */
    void start() {
        try {
            sender.ensureTemplates();
        } catch (HistoryOpenSearchException e) {
            LOG.error("history-opensearch: cannot install the index templates: {}", e.getMessage(), e);
            if (options.failurePolicy() == FailurePolicy.FAIL) {
                throw new HistoryOpenSearchException(
                        "history-opensearch: cannot install the index templates at startup: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void handleEvent(HistoryEvent historyEvent) {
        handleEvents(List.of(historyEvent));
    }

    @Override
    public void handleEvents(List<HistoryEvent> historyEvents) {
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            throw new HistoryOpenSearchException(
                    "History events must be handled inside an engine command; there is no command context on this thread");
        }
        Instant now = ClockUtil.getCurrentTime().toInstant();
        List<HistoryDocument> accepted = new ArrayList<>(historyEvents.size());
        for (HistoryEvent historyEvent : historyEvents) {
            HistoryDocument document = mapper.map(historyEvent, now);
            if (document != null) {
                accepted.add(document);
            }
        }
        if (!accepted.isEmpty()) {
            buffers.computeIfAbsent(commandContext, this::newBuffer).addAll(accepted);
        }
    }

    private List<HistoryDocument> newBuffer(CommandContext commandContext) {
        List<HistoryDocument> buffer = new ArrayList<>();
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

    private void publish(List<HistoryDocument> documents) {
        if (processor == null) {
            Outcome outcome = sender.send(documents);
            completed(outcome);
            if (!outcome.succeeded() && options.failurePolicy() == FailurePolicy.FAIL) {
                throw failure(outcome, documents.size());
            }
            return;
        }
        try {
            processor.submit(documents);
        } catch (HistoryOpenSearchException e) {
            failed.addAndGet(documents.size());
            LOG.error(
                    "Cannot queue history documents after commit; the engine transaction is already committed: {}",
                    e.getMessage(),
                    e);
            if (options.failurePolicy() == FailurePolicy.FAIL) {
                throw new HistoryOpenSearchException(
                        e.getMessage() + " (the engine transaction is already committed and is NOT rolled back)", e);
            }
        }
    }

    /** Counts an outcome and logs every failure with its context; never logs field values. */
    private void completed(Outcome outcome) {
        indexed.addAndGet(outcome.written());
        failed.addAndGet(outcome.failures().size());
        for (Outcome.Failure failure : outcome.failures()) {
            LOG.error(
                    "OpenSearch did not accept a history document after commit ({}); the engine transaction is already committed",
                    failure.description(),
                    failure.cause());
        }
    }

    private static HistoryOpenSearchException failure(Outcome outcome, int total) {
        Outcome.Failure first = outcome.failures().get(0);
        HistoryOpenSearchException exception = new HistoryOpenSearchException(
                "Writing " + outcome.failures().size() + " of " + total
                        + " history document(s) to OpenSearch failed after the engine transaction committed"
                        + " (the transaction is NOT rolled back). First failure: " + first.description() + ": "
                        + first.cause(),
                first.cause());
        outcome.failures().stream().skip(1).forEach(f -> exception.addSuppressed(f.cause()));
        return exception;
    }

    /**
     * Writes everything queued so far and waits for the result. Only useful in {@link SendMode#ASYNC}
     * (synchronous writes are done when the command returns); does nothing otherwise. Documents become
     * searchable after the next index refresh, typically one second later.
     *
     * @param timeout longest wait
     * @throws HistoryOpenSearchException when the queue does not drain in time
     */
    public void flush(Duration timeout) {
        if (processor != null) {
            processor.flush(timeout);
        }
    }

    /** Document operations OpenSearch accepted (one per document and bulk request, events of one document are merged). */
    public long getIndexedCount() {
        return indexed.get();
    }

    /** Document operations that could not be queued or written. */
    public long getFailedCount() {
        return failed.get();
    }

    /** Buffered events dropped because their transaction rolled back. */
    public long getDiscardedCount() {
        return discarded.get();
    }

    /** Retries after failed requests or rejected documents. */
    public long getRetryCount() {
        return sender.getRetryCount();
    }

    /** The settings this handler was created with. */
    public HistoryOpenSearchOptions getOptions() {
        return options;
    }

    /** Writes what is queued (waiting up to {@code sendTimeout}) and stops the writer thread. Idempotent. */
    @Override
    public void close() {
        if (processor != null) {
            processor.close();
        }
    }
}

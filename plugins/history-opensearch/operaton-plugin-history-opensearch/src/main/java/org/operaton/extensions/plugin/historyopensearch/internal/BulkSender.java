/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes history documents to OpenSearch: installs the index templates, places every document in its
 * index, merges the changes of one document into a single operation and sends them in bulk requests,
 * retrying what OpenSearch rejects as overloaded. Each operation is a scripted upsert, so the document
 * is created by the first event and updated by every later one, whatever order the fields arrive in.
 * Thread-safe.
 */
public final class BulkSender {

    private static final Logger LOG = LoggerFactory.getLogger(BulkSender.class);

    /**
     * Merges the fields of the event into the document and derives the duration when both times are
     * known. Painless; runs inside OpenSearch, so concurrent updates of one document are serialized there.
     */
    static final String MERGE_SCRIPT =
            "for (def e : params.doc.entrySet()) { ctx._source[e.getKey()] = e.getValue(); } "
                    + "if (ctx._source.durationInMillis == null && ctx._source.startTime != null && ctx._source.endTime != null) { "
                    + "ctx._source.durationInMillis = Instant.parse(ctx._source.endTime).toEpochMilli()"
                    + " - Instant.parse(ctx._source.startTime).toEpochMilli(); }";

    private static final String BULK_PATH =
            "/_bulk?filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason";
    private static final int REASON_LIMIT = 300;

    private final OpenSearchClient client;
    private final ObjectMapper json;
    private final HistoryOpenSearchOptions options;
    private final Retrier retrier;
    private final IndexResolver resolver;
    private final IndexTemplates templates;
    private final Object templateLock = new Object();
    private volatile boolean templatesInstalled;

    /** Creates the sender for the options; does not contact OpenSearch yet. */
    public BulkSender(HistoryOpenSearchOptions options, ObjectMapper json) {
        this.options = options;
        this.json = json;
        this.client = new OpenSearchClient(options.connection(), json);
        this.retrier = new Retrier(options.maxRetries(), options.initialBackoff(), options.maxBackoff());
        this.resolver = new IndexResolver(client, json, options.indexNames(), retrier, options.lookupCacheSize());
        this.templates = new IndexTemplates(json, options);
    }

    /** How many retries happened so far (failed requests and rejected documents that were sent again). */
    public long getRetryCount() {
        return retrier.retries();
    }

    /**
     * Installs the index templates unless they are installed already or {@code manageTemplates} is off.
     * Idempotent: running it against a cluster that already has the templates replaces them with the
     * same definition.
     *
     * @throws HistoryOpenSearchException when OpenSearch cannot be reached or refuses a template
     */
    public void ensureTemplates() {
        if (templatesInstalled) {
            return;
        }
        synchronized (templateLock) {
            if (templatesInstalled) {
                return;
            }
            if (options.manageTemplates()) {
                for (Map.Entry<String, ObjectNode> template : templates.all().entrySet()) {
                    String body = write(template.getValue());
                    retrier.call(
                            "installing index template " + template.getKey(),
                            () -> client.execute(
                                    "PUT", "/_index_template/" + template.getKey(), body, "application/json"));
                    LOG.debug("history-opensearch: index template {} installed", template.getKey());
                }
            }
            templatesInstalled = true;
        }
    }

    /**
     * Writes the documents. Never throws for a failed write: every operation that was not accepted is
     * reported in the {@link Outcome}.
     */
    public Outcome send(List<HistoryDocument> documents) {
        if (documents.isEmpty()) {
            return new Outcome(0, List.of());
        }
        List<Outcome.Failure> failures = new ArrayList<>();
        List<Operation> operations;
        try {
            ensureTemplates();
            Instant now = ClockUtil.getCurrentTime().toInstant();
            operations = coalesce(documents, resolver.resolve(documents, now));
        } catch (HistoryOpenSearchException e) {
            for (HistoryDocument document : documents) {
                failures.add(new Outcome.Failure(document.describe(), e));
            }
            return new Outcome(0, failures);
        }
        long written = 0;
        for (int from = 0; from < operations.size(); from += options.batchSize()) {
            List<Operation> chunk = operations.subList(from, Math.min(operations.size(), from + options.batchSize()));
            written += sendChunk(chunk, failures);
        }
        return new Outcome(written, failures);
    }

    /**
     * One operation per (index, id): the fields of consecutive events for the same document are merged
     * in order, later values win. Moving a later event of a document up to its first one cannot change
     * the result, because operations on different documents are independent.
     */
    private static List<Operation> coalesce(List<HistoryDocument> documents, List<String> indices) {
        Map<String, Operation> merged = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            HistoryDocument document = documents.get(i);
            String index = indices.get(i);
            merged.computeIfAbsent(index + "\u0000" + document.id(), k -> new Operation(index, document))
                    .merge(document);
        }
        return new ArrayList<>(merged.values());
    }

    private long sendChunk(List<Operation> chunk, List<Outcome.Failure> failures) {
        List<Operation> pending = chunk;
        long written = 0;
        for (int attempt = 0; ; attempt++) {
            JsonNode response = null;
            RestException failure = null;
            try {
                response = client.execute("POST", BULK_PATH, body(pending), "application/x-ndjson");
            } catch (RestException e) {
                failure = e;
            }
            if (failure != null) {
                if (failure.isRetryable() && attempt < retrier.maxRetries()) {
                    LOG.warn(
                            "history-opensearch: bulk request failed ({}); retry {} of {}",
                            failure.getMessage(),
                            attempt + 1,
                            retrier.maxRetries());
                    try {
                        retrier.backoff(attempt, failure);
                        continue;
                    } catch (RestException interrupted) {
                        failure = interrupted;
                    }
                }
                RestException reported = failure;
                pending.forEach(op -> failures.add(new Outcome.Failure(op.describe(), reported)));
                return written;
            }
            if (!response.path("errors").asBoolean(false)) {
                return written + pending.size();
            }
            List<Operation> retry = new ArrayList<>();
            RestException lastRetryable = null;
            JsonNode items = response.path("items");
            if (items.size() != pending.size()) {
                RestException e = new RestException(
                        "bulk response has " + items.size() + " items for " + pending.size() + " operations",
                        200,
                        false,
                        null);
                pending.forEach(op -> failures.add(new Outcome.Failure(op.describe(), e)));
                return written;
            }
            for (int i = 0; i < pending.size(); i++) {
                Operation op = pending.get(i);
                JsonNode result = items.get(i).elements().next();
                int status = result.path("status").asInt();
                if (status / 100 == 2) {
                    written++;
                    continue;
                }
                RestException cause = new RestException(
                        "HTTP " + status + " "
                                + result.path("error").path("type").asText("") + ": " + reason(result),
                        status,
                        status == 429 || status == 409 || status >= 500,
                        null);
                if (cause.isRetryable() && attempt < retrier.maxRetries()) {
                    retry.add(op);
                    lastRetryable = cause;
                } else {
                    failures.add(new Outcome.Failure(op.describe(), cause));
                }
            }
            if (retry.isEmpty()) {
                return written;
            }
            LOG.warn(
                    "history-opensearch: OpenSearch rejected {} of {} document operations; retry {} of {}",
                    retry.size(),
                    pending.size(),
                    attempt + 1,
                    retrier.maxRetries());
            try {
                retrier.backoff(attempt, lastRetryable);
            } catch (RestException interrupted) {
                retry.forEach(op -> failures.add(new Outcome.Failure(op.describe(), interrupted)));
                return written;
            }
            pending = retry;
        }
    }

    private static String reason(JsonNode result) {
        String reason = result.path("error").path("reason").asText("");
        return reason.length() > REASON_LIMIT ? reason.substring(0, REASON_LIMIT) + "..." : reason;
    }

    private String body(List<Operation> operations) {
        StringBuilder ndjson = new StringBuilder();
        for (Operation op : operations) {
            ObjectNode action = json.createObjectNode();
            ObjectNode update = action.putObject("update");
            update.put("_index", op.index);
            update.put("_id", op.id);
            update.put("retry_on_conflict", 3);
            ObjectNode source = json.createObjectNode();
            source.put("scripted_upsert", true);
            ObjectNode script = source.putObject("script");
            script.put("lang", "painless");
            script.put("source", MERGE_SCRIPT);
            script.putObject("params").set("doc", json.valueToTree(op.fields));
            source.putObject("upsert");
            ndjson.append(write(action)).append('\n').append(write(source)).append('\n');
        }
        return ndjson.toString();
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new HistoryOpenSearchException("Cannot serialize a request to OpenSearch", e);
        }
    }

    /** One document operation: target, id and the merged fields. */
    private static final class Operation {
        final String index;
        final String id;
        final Map<String, Object> fields = new LinkedHashMap<>();
        private HistoryDocument last;

        Operation(String index, HistoryDocument first) {
            this.index = index;
            this.id = first.id();
        }

        void merge(HistoryDocument document) {
            fields.putAll(document.fields());
            last = document;
        }

        String describe() {
            return "index=" + index + ", " + last.describe();
        }
    }
}

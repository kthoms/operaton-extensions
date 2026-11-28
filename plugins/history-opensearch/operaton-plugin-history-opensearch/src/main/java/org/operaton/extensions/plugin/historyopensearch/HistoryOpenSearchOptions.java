/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import org.operaton.extensions.plugin.historyopensearch.internal.HistoryEventCategories;
import org.operaton.extensions.plugin.historyopensearch.internal.IndexNames;

/**
 * Validated, immutable settings of an {@link OpenSearchHistoryEventHandler}.
 *
 * @param connection endpoints, credentials, TLS, timeouts
 * @param indexPrefix first part of every index name; the indices are {@code <prefix>-process-instances},
 *     {@code -activity-instances}, {@code -tasks}, {@code -variables}, {@code -incidents}, each followed
 *     by the date part
 * @param indexDatePattern {@link java.time.format.DateTimeFormatter} pattern of the date part (UTC), e.g.
 *     {@code yyyy.MM} for monthly indices; empty for one index per kind without a date part
 * @param numberOfShards primary shards of new indices; {@code null} leaves it to the cluster default
 * @param numberOfReplicas replicas of new indices; {@code null} leaves it to the cluster default
 * @param manageTemplates whether the plugin installs and updates the index templates at startup
 * @param eventTypes categories ({@code process-instance}) or category and event
 *     ({@code process-instance.end}) to index; empty indexes every supported event
 * @param failurePolicy reaction to documents that cannot be written
 * @param sendMode background queue or synchronous write
 * @param batchSize documents per bulk request (background mode: the limit that triggers a request)
 * @param flushInterval background mode: longest time a queued document waits before its bulk request
 * @param queueCapacity background mode: documents the queue holds before committing threads wait
 * @param maxRetries how often a failed request or a rejected document is retried
 * @param initialBackoff wait before the first retry; doubles per retry
 * @param maxBackoff upper limit of the wait between retries
 * @param sendTimeout synchronous mode and shutdown: longest wait for the documents of one transaction
 *     or for the queue to drain; background mode: how long a committing thread waits for a free queue slot
 * @param includeVariableValues whether variable documents carry the variable value
 * @param maxVariableValueLength longest text value indexed; longer values are cut and flagged
 * @param lookupCacheSize how many document-to-index assignments are remembered
 */
public record HistoryOpenSearchOptions(
        OpenSearchConnection connection,
        String indexPrefix,
        String indexDatePattern,
        Integer numberOfShards,
        Integer numberOfReplicas,
        boolean manageTemplates,
        Set<String> eventTypes,
        FailurePolicy failurePolicy,
        SendMode sendMode,
        int batchSize,
        Duration flushInterval,
        int queueCapacity,
        int maxRetries,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration sendTimeout,
        boolean includeVariableValues,
        int maxVariableValueLength,
        int lookupCacheSize) {

    /** Validates and copies the settings. */
    public HistoryOpenSearchOptions {
        if (connection == null) {
            throw new IllegalArgumentException("history-opensearch: connection settings are required");
        }
        IndexNames.validate(indexPrefix, indexDatePattern);
        if (numberOfShards != null && numberOfShards < 1) {
            throw new IllegalArgumentException(
                    "history-opensearch: numberOfShards must be at least 1, was " + numberOfShards);
        }
        if (numberOfReplicas != null && numberOfReplicas < 0) {
            throw new IllegalArgumentException(
                    "history-opensearch: numberOfReplicas must not be negative, was " + numberOfReplicas);
        }
        Set<String> filter = new TreeSet<>();
        if (eventTypes != null) {
            for (String entry : eventTypes) {
                checkCategoryOrEvent(entry);
                filter.add(entry);
            }
        }
        eventTypes = Set.copyOf(filter);
        if (failurePolicy == null) {
            throw new IllegalArgumentException("history-opensearch: failurePolicy must not be null");
        }
        if (sendMode == null) {
            throw new IllegalArgumentException("history-opensearch: sendMode must not be null");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("history-opensearch: batchSize must be at least 1, was " + batchSize);
        }
        positive(flushInterval, "flushInterval");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "history-opensearch: queueCapacity must be at least 1, was " + queueCapacity);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                    "history-opensearch: maxRetries must not be negative, was " + maxRetries);
        }
        positive(initialBackoff, "initialBackoff");
        positive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "history-opensearch: maxBackoff must not be shorter than initialBackoff");
        }
        positive(sendTimeout, "sendTimeout");
        if (maxVariableValueLength < 1) {
            throw new IllegalArgumentException(
                    "history-opensearch: maxVariableValueLength must be at least 1, was " + maxVariableValueLength);
        }
        if (lookupCacheSize < 1) {
            throw new IllegalArgumentException(
                    "history-opensearch: lookupCacheSize must be at least 1, was " + lookupCacheSize);
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("history-opensearch: " + name + " must be positive, was " + value);
        }
    }

    private static void checkCategoryOrEvent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("history-opensearch: event type must not be blank");
        }
        String category = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
        String event = value.contains(".") ? value.substring(value.indexOf('.') + 1) : null;
        if (!HistoryEventCategories.KNOWN.contains(category) || (event != null && event.isBlank())) {
            throw new IllegalArgumentException(
                    "history-opensearch: unknown event type '" + value + "'; indexed categories: "
                            + new TreeSet<>(HistoryEventCategories.KNOWN)
                            + " (optionally followed by .<event>, e.g. process-instance.end)");
        }
    }

    /** Whether an event of this category and event name is indexed. */
    public boolean accepts(String category, String event) {
        return eventTypes.isEmpty()
                || eventTypes.contains(category)
                || (event != null && eventTypes.contains(category + "." + event));
    }

    /** The index naming scheme these options describe. */
    public IndexNames indexNames() {
        return new IndexNames(indexPrefix, indexDatePattern);
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.example;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Analytics view built from the history events on Kafka: instance counts, outcomes and average
 * activity durations. Events are de-duplicated by event id (delivery is at-least-once after a
 * retry); all methods are synchronized, the view is read by the REST controller and written by the
 * consumer thread.
 */
@Component
public class LoanAnalytics {

    private static final int REMEMBERED_EVENT_IDS = 50_000;

    /** A point-in-time copy of the view. */
    public record Snapshot(
            long started,
            long completed,
            long inFlight,
            Map<String, Long> outcomes,
            Map<String, Double> averageDurationMillisByActivity) {}

    private final Set<String> seen = new HashSet<>();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> outcomes = new TreeMap<>();
    private final Map<String, java.time.Instant> activityStarts = new java.util.HashMap<>();
    private final Map<String, long[]> durations = new TreeMap<>();
    private long started;
    private long completed;

    /** Applies one event (the JSON envelope the plugin publishes). */
    public synchronized void apply(JsonNode event) {
        String id = event.path("id").asText();
        if (!seen.add(id)) {
            return;
        }
        seenOrder.add(id);
        if (seenOrder.size() > REMEMBERED_EVENT_IDS) {
            seen.remove(seenOrder.poll());
        }
        JsonNode data = event.path("data");
        switch (event.path("type").asText()) {
            case "process-instance.start" -> {
                started++;
                running.add(data.path("processInstanceId").asText());
            }
            case "process-instance.end" -> {
                completed++;
                running.remove(data.path("processInstanceId").asText());
            }
            case "activity-instance.start" ->
                activityStarts.put(
                        data.path("activityInstanceId").asText(),
                        java.time.Instant.parse(data.path("startTime").asText()));
            case "activity-instance.end" -> activityEnded(data);
            default -> {}
        }
    }

    /**
     * History events are deltas of the history row: the end event carries the end time but not the
     * start time, so the start event is remembered and joined by activity instance id.
     */
    private void activityEnded(JsonNode data) {
        String type = data.path("activityType").asText();
        String activityId = data.path("activityId").asText();
        java.time.Instant start =
                activityStarts.remove(data.path("activityInstanceId").asText());
        if ("noneEndEvent".equals(type)) {
            outcomes.merge(activityId, 1L, Long::sum);
        } else if (start != null) {
            long millis = java.time.Duration.between(
                            start, java.time.Instant.parse(data.path("endTime").asText()))
                    .toMillis();
            long[] sumAndCount = durations.computeIfAbsent(activityId, key -> new long[2]);
            sumAndCount[0] += millis;
            sumAndCount[1]++;
        }
    }

    public synchronized Snapshot snapshot() {
        Map<String, Double> averages = new TreeMap<>();
        durations.forEach((activity, sumAndCount) -> averages.put(activity, (double) sumAndCount[0] / sumAndCount[1]));
        return new Snapshot(started, completed, running.size(), Map.copyOf(outcomes), Map.copyOf(averages));
    }
}

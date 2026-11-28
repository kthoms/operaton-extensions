/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters of what the notifier did. Read a consistent-enough view with {@link #snapshot()}. */
public final class NotificationStatistics {

    /** Counters of one channel. */
    public record ChannelStatistics(long digestsSent, long digestsFailed, long incidentsSent, long incidentsFailed) {}

    /** Point-in-time copy of all counters. */
    public record Snapshot(
            long incidentsObserved,
            long collectionFailures,
            long incidentsDropped,
            Map<String, ChannelStatistics> channels) {}

    private static final class Counters {
        final LongAdder digestsSent = new LongAdder();
        final LongAdder digestsFailed = new LongAdder();
        final LongAdder incidentsSent = new LongAdder();
        final LongAdder incidentsFailed = new LongAdder();
    }

    private final LongAdder observed = new LongAdder();
    private final LongAdder collectionFailures = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final Map<String, Counters> channels = new ConcurrentHashMap<>();

    void observed() {
        observed.increment();
    }

    void collectionFailed() {
        collectionFailures.increment();
    }

    void dropped(long incidents) {
        dropped.add(incidents);
    }

    void sent(String channel, long incidents) {
        Counters counters = channels.computeIfAbsent(channel, c -> new Counters());
        counters.digestsSent.increment();
        counters.incidentsSent.add(incidents);
    }

    void failed(String channel, long incidents) {
        Counters counters = channels.computeIfAbsent(channel, c -> new Counters());
        counters.digestsFailed.increment();
        counters.incidentsFailed.add(incidents);
    }

    /** Incidents that were created while the notifier was active. */
    public long incidentsObserved() {
        return observed.sum();
    }

    /** Incidents for which collecting the notification data failed (they got no notification). */
    public long collectionFailures() {
        return collectionFailures.sum();
    }

    /** Incidents dropped because a buffer or the sender queue was full, or the notifier was closed. */
    public long incidentsDropped() {
        return dropped.sum();
    }

    /** Digests delivered successfully over the channel. */
    public long digestsSent(String channel) {
        Counters counters = channels.get(channel);
        return counters == null ? 0 : counters.digestsSent.sum();
    }

    /** Digests the channel failed to deliver. */
    public long digestsFailed(String channel) {
        Counters counters = channels.get(channel);
        return counters == null ? 0 : counters.digestsFailed.sum();
    }

    public Snapshot snapshot() {
        Map<String, ChannelStatistics> copy = new TreeMap<>();
        channels.forEach((name, c) -> copy.put(
                name,
                new ChannelStatistics(
                        c.digestsSent.sum(), c.digestsFailed.sum(), c.incidentsSent.sum(), c.incidentsFailed.sum())));
        return new Snapshot(observed.sum(), collectionFailures.sum(), dropped.sum(), Map.copyOf(copy));
    }
}

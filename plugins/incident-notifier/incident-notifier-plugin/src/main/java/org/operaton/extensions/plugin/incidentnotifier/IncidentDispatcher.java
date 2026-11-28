/*
 * Copyright 2020 “J-IT“ IT-Dienstleistungs GesmbH, Vienna, Austria
 * Modifications Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/email-incident-notification-plugin (Apache-2.0)
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batches incidents per process definition and delivers them asynchronously to the channels.
 *
 * <p>Throttling is leading plus trailing edge: the first incident of a quiet period is delivered at
 * once, incidents arriving within {@code throttleMillis} after a delivery are buffered and delivered
 * as one digest when the window ends. With a throttle of 0 every incident is delivered on its own.
 *
 * <p>Thread-safe. {@link #submit(IncidentInfo)} never blocks on I/O and never throws: when an
 * incident cannot be accepted it is counted as dropped. Delivery failures are logged with context and
 * counted per channel, they never reach the caller.
 */
final class IncidentDispatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentDispatcher.class);
    private static final int SENDER_QUEUE_CAPACITY = 1000;
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final class WindowState {
        final Deque<IncidentInfo> buffer = new ArrayDeque<>();
        long lastSentNanos;
        boolean sentBefore;
        boolean flushScheduled;
        int suppressed;
    }

    private final List<NotificationChannel> channels;
    private final long throttleNanos;
    private final int maxBuffered;
    private final NotificationStatistics statistics;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ThreadPoolExecutor senders;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();
    private volatile boolean closed;

    IncidentDispatcher(
            List<NotificationChannel> channels,
            long throttleMillis,
            int maxBuffered,
            int senderThreads,
            NotificationStatistics statistics) {
        this.channels = List.copyOf(channels);
        this.throttleNanos = TimeUnit.MILLISECONDS.toNanos(throttleMillis);
        this.maxBuffered = maxBuffered;
        this.statistics = statistics;
        this.scheduler = new ScheduledThreadPoolExecutor(1, daemonThreads("incident-notifier-timer"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.senders = new ThreadPoolExecutor(
                senderThreads,
                senderThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(SENDER_QUEUE_CAPACITY),
                daemonThreads("incident-notifier-sender"));
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Accepts an incident for notification. */
    void submit(IncidentInfo incident) {
        if (closed) {
            statistics.dropped(1);
            LOG.warn("Incident {} not notified: the incident notifier is closed", incident.id());
            return;
        }
        String key = incident.processDefinitionKey();
        if (throttleNanos == 0) {
            dispatch(new IncidentDigest(key, List.of(incident), 0));
            return;
        }
        WindowState window = windows.computeIfAbsent(key, k -> new WindowState());
        IncidentDigest immediate = null;
        synchronized (window) {
            long now = System.nanoTime();
            long waitNanos = window.sentBefore ? window.lastSentNanos + throttleNanos - now : 0;
            if (!window.flushScheduled && waitNanos <= 0) {
                window.sentBefore = true;
                window.lastSentNanos = now;
                immediate = new IncidentDigest(key, List.of(incident), 0);
            } else {
                if (window.buffer.size() >= maxBuffered) {
                    window.buffer.pollFirst();
                    window.suppressed++;
                    statistics.dropped(1);
                    LOG.warn(
                            "Notification buffer of process definition {} is full ({} incidents), dropped the oldest",
                            key,
                            maxBuffered);
                }
                window.buffer.addLast(incident);
                if (!window.flushScheduled) {
                    window.flushScheduled = true;
                    try {
                        scheduler.schedule(() -> flush(key, window), waitNanos, TimeUnit.NANOSECONDS);
                    } catch (RejectedExecutionException e) {
                        window.flushScheduled = false;
                        window.buffer.pollLast();
                        statistics.dropped(1);
                        LOG.warn("Incident {} not notified: the incident notifier is closed", incident.id());
                    }
                }
            }
        }
        if (immediate != null) {
            dispatch(immediate);
        }
    }

    private void flush(String key, WindowState window) {
        IncidentDigest digest = drain(key, window);
        if (digest != null) {
            dispatch(digest);
        }
    }

    private IncidentDigest drain(String key, WindowState window) {
        synchronized (window) {
            window.flushScheduled = false;
            if (window.buffer.isEmpty()) {
                return null;
            }
            List<IncidentInfo> incidents = new ArrayList<>(window.buffer);
            int suppressed = window.suppressed;
            window.buffer.clear();
            window.suppressed = 0;
            window.sentBefore = true;
            window.lastSentNanos = System.nanoTime();
            return new IncidentDigest(key, incidents, suppressed);
        }
    }

    private void dispatch(IncidentDigest digest) {
        try {
            senders.execute(() -> deliver(digest));
        } catch (RejectedExecutionException e) {
            statistics.dropped(digest.total());
            LOG.warn(
                    "Dropped {} incident(s) of process definition {}: the sender queue is full or the notifier is closed",
                    digest.total(),
                    digest.processDefinitionKey());
        }
    }

    private void deliver(IncidentDigest digest) {
        for (NotificationChannel channel : channels) {
            try {
                channel.send(digest);
                statistics.sent(channel.name(), digest.total());
                LOG.debug(
                        "Notified {} incident(s) of process definition {} over {}",
                        digest.total(),
                        digest.processDefinitionKey(),
                        channel.name());
            } catch (Exception | LinkageError e) {
                statistics.failed(channel.name(), digest.total());
                LOG.error(
                        "Notification over channel {} failed for {} incident(s) of process definition {} (incidents: {})",
                        channel.name(),
                        digest.total(),
                        digest.processDefinitionKey(),
                        digest.incidents().stream().map(IncidentInfo::id).collect(Collectors.joining(",")),
                        e);
            }
        }
    }

    /** Delivers what is still buffered, waits for running deliveries and stops the threads. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
        windows.forEach((key, window) -> {
            IncidentDigest digest = drain(key, window);
            if (digest != null) {
                dispatch(digest);
            }
        });
        senders.shutdown();
        try {
            if (!senders.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                int pending = senders.shutdownNow().size();
                LOG.warn("Incident notifier stopped with {} undelivered digest(s)", pending);
            }
        } catch (InterruptedException e) {
            senders.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

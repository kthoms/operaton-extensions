/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

/**
 * A destination for incident digests (mail, webhook, ...). Implementations must be thread-safe:
 * digests are delivered from a pool of sender threads.
 *
 * <p>{@link #send(IncidentDigest)} must throw when the delivery failed; the dispatcher logs the
 * failure with context and counts it, it never reaches the engine.
 */
public interface NotificationChannel {

    /** Short stable name used in logs and statistics, for example {@code email}. */
    String name();

    /**
     * Delivers the digest.
     *
     * @throws Exception if the delivery failed
     */
    void send(IncidentDigest digest) throws Exception;
}

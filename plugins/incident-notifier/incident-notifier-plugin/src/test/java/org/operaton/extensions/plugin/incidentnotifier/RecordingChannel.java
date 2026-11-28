/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test channel that records digests, and can be told to fail. */
final class RecordingChannel implements NotificationChannel {

    final List<IncidentDigest> digests = new CopyOnWriteArrayList<>();
    volatile boolean failing;
    private final String name;

    RecordingChannel(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void send(IncidentDigest digest) {
        if (failing) {
            throw new IllegalStateException("channel " + name + " is down");
        }
        digests.add(digest);
    }

    List<IncidentInfo> incidents() {
        return digests.stream().flatMap(d -> d.incidents().stream()).toList();
    }
}

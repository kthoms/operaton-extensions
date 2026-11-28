/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IncidentDispatcherTest {

    final RecordingChannel channel = new RecordingChannel("rec");
    final NotificationStatistics stats = new NotificationStatistics();
    IncidentDispatcher dispatcher;

    @AfterEach
    void close() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    static IncidentInfo incident(String id, String key) {
        return new IncidentInfo(
                id, "failedJob", "m", "a", "d", key, key, "pi", "e", null, null, Instant.now(), "", null, null);
    }

    @Test
    void withoutThrottleEveryIncidentIsDeliveredOnItsOwn() {
        dispatcher = new IncidentDispatcher(List.of(channel), 0, 10, 1, stats);

        dispatcher.submit(incident("1", "p"));
        dispatcher.submit(incident("2", "p"));

        await().untilAsserted(() -> assertThat(channel.digests).hasSize(2));
        assertThat(channel.digests).allSatisfy(d -> assertThat(d.incidents()).hasSize(1));
    }

    @Test
    void throttleSendsTheFirstAtOnceAndTheRestAsOneDigest() {
        dispatcher = new IncidentDispatcher(List.of(channel), 600, 10, 1, stats);

        dispatcher.submit(incident("1", "p"));
        dispatcher.submit(incident("2", "p"));
        dispatcher.submit(incident("3", "p"));
        dispatcher.submit(incident("other", "q"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(channel.digests).hasSize(3));
        assertThat(channel.digests.stream()
                        .filter(d -> d.processDefinitionKey().equals("p"))
                        .map(d -> d.incidents().stream().map(IncidentInfo::id).toList()))
                .containsExactlyInAnyOrder(List.of("1"), List.of("2", "3"));
        assertThat(channel.digests.stream().filter(d -> d.processDefinitionKey().equals("q")))
                .hasSize(1);
    }

    @Test
    void anOverfullBufferDropsTheOldestAndReportsIt() {
        dispatcher = new IncidentDispatcher(List.of(channel), 600, 2, 1, stats);

        for (int i = 1; i <= 5; i++) {
            dispatcher.submit(incident(String.valueOf(i), "p"));
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(channel.digests).hasSize(2));
        IncidentDigest digest = channel.digests.get(1);
        assertThat(digest.incidents().stream().map(IncidentInfo::id)).containsExactly("4", "5");
        assertThat(digest.suppressed()).isEqualTo(2);
        assertThat(stats.incidentsDropped()).isEqualTo(2);
    }

    @Test
    void aFailingChannelDoesNotStopTheOthersAndIsCounted() {
        RecordingChannel broken = new RecordingChannel("broken");
        broken.failing = true;
        dispatcher = new IncidentDispatcher(List.of(broken, channel), 0, 10, 1, stats);

        dispatcher.submit(incident("1", "p"));

        await().untilAsserted(() -> assertThat(channel.digests).hasSize(1));
        assertThat(stats.digestsFailed("broken")).isEqualTo(1);
        assertThat(stats.digestsSent("rec")).isEqualTo(1);
        assertThat(stats.snapshot().channels().get("broken").incidentsFailed()).isEqualTo(1);
    }

    @Test
    void closeDeliversWhatIsStillBuffered() {
        dispatcher = new IncidentDispatcher(List.of(channel), 60_000, 10, 1, stats);
        dispatcher.submit(incident("1", "p"));
        dispatcher.submit(incident("2", "p"));

        dispatcher.close();

        assertThat(channel.digests).hasSize(2);
        assertThat(channel.digests.get(1).incidents())
                .extracting(IncidentInfo::id)
                .containsExactly("2");
    }

    @Test
    void incidentsAfterCloseAreCountedAsDropped() {
        dispatcher = new IncidentDispatcher(List.of(channel), 0, 10, 1, stats);
        dispatcher.close();

        dispatcher.submit(incident("1", "p"));

        assertThat(stats.incidentsDropped()).isEqualTo(1);
        assertThat(channel.digests).isEmpty();
    }
}

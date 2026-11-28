/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** Generic, Slack and Teams webhooks fed by real incidents of an embedded engine. */
class WebhookEngineTest {

    static final TestHttpServer SERVER = new TestHttpServer();
    static final IncidentNotifierPlugin PLUGIN = new IncidentNotifierPlugin();

    static {
        PLUGIN.setThrottleMillis(0);
        PLUGIN.setCockpitBaseUrl("http://cockpit.test");
        PLUGIN.setWebhookUrl(SERVER.url("/generic"));
        PLUGIN.setWebhookHeaders(Map.of("Authorization", "Bearer secret"));
        PLUGIN.setSlackWebhookUrl(SERVER.url("/slack"));
        PLUGIN.setTeamsWebhookUrl(SERVER.url("/teams"));
    }

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .configurator(cfg -> cfg.getProcessEnginePlugins().add(PLUGIN))
            .closeEngineAfterAllTests()
            .build();

    ProcessEngine processEngine;

    @BeforeEach
    void setUp() {
        processEngine = engine.getProcessEngine();
        SERVER.requests.clear();
        SERVER.status = 200;
    }

    @AfterEach
    void tearDown() {
        EngineFixtures.cleanUp(processEngine);
    }

    @AfterAll
    static void stop() {
        PLUGIN.close();
        SERVER.close();
    }

    @Test
    void postsGenericSlackAndTeamsPayloads() {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-hooks");

        String instanceId = EngineFixtures.startAndFail(processEngine, "pay-hooks", "bk-1", Map.of());

        await().untilAsserted(() -> assertThat(SERVER.requests).hasSize(3));
        TestHttpServer.Request generic = request("/generic");
        assertThat(generic.headers()).containsEntry("authorization", "Bearer secret");
        assertThat(generic.headers().get("content-type")).startsWith("application/json");
        assertThat(generic.body())
                .contains("\"event\":\"operaton.incident.digest\"")
                .contains("\"processDefinitionKey\":\"pay-hooks\"")
                .contains("\"processInstanceId\":\"" + instanceId + "\"")
                .contains("\"businessKey\":\"bk-1\"")
                .contains("\"type\":\"failedJob\"")
                .contains("payment gateway unavailable")
                .contains("#/process-instance/" + instanceId);
        assertThat(request("/slack").body())
                .startsWith("{\"text\":\"*[Operaton] 1 incident(s)")
                .contains(instanceId);
        assertThat(request("/teams").body())
                .contains("\"type\":\"message\"")
                .contains("application/vnd.microsoft.card.adaptive")
                .contains("\"type\":\"AdaptiveCard\"")
                .contains(instanceId);
    }

    @Test
    void failingWebhookIsCountedAndDoesNotAffectTheEngine() {
        SERVER.status = 500;
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-hooks-500");
        long before = PLUGIN.getStatistics().digestsFailed("webhook");

        EngineFixtures.startAndFail(processEngine, "pay-hooks-500", null, Map.of());

        assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
                .isEqualTo(1);
        await().untilAsserted(() ->
                assertThat(PLUGIN.getStatistics().digestsFailed("webhook")).isEqualTo(before + 1));
        assertThat(PLUGIN.getStatistics().digestsFailed("slack")).isPositive();
    }

    private static TestHttpServer.Request request(String path) {
        return SERVER.requests.stream()
                .filter(r -> r.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no request to " + path));
    }
}

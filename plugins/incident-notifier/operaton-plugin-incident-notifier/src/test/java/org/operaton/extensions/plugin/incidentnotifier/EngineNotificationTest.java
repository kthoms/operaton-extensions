/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

class EngineNotificationTest {

    static final IncidentNotifierPlugin PLUGIN = new IncidentNotifierPlugin();
    static final RecordingChannel CHANNEL = new RecordingChannel("recording");

    static {
        PLUGIN.setThrottleMillis(0);
        PLUGIN.setCockpitBaseUrl("http://cockpit.test:8080/operaton/");
        PLUGIN.addChannel(CHANNEL);
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
        CHANNEL.digests.clear();
        CHANNEL.failing = false;
    }

    @AfterEach
    void tearDown() {
        EngineFixtures.cleanUp(processEngine);
    }

    @Test
    void failedJobIncidentIsPersistedAndNotified() {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-job");

        String instanceId = EngineFixtures.startAndFail(processEngine, "pay-job", "order-4711", Map.of());

        assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
                .isEqualTo(1);
        await().untilAsserted(() -> assertThat(CHANNEL.incidents()).hasSize(1));
        IncidentInfo info = CHANNEL.incidents().get(0);
        assertThat(info.type()).isEqualTo("failedJob");
        assertThat(info.message()).contains("payment gateway unavailable");
        assertThat(info.activityId()).isEqualTo("charge");
        assertThat(info.processDefinitionKey()).isEqualTo("pay-job");
        assertThat(info.processDefinitionName()).isEqualTo("Payment pay-job");
        assertThat(info.processInstanceId()).isEqualTo(instanceId);
        assertThat(info.businessKey()).isEqualTo("order-4711");
        assertThat(info.cockpitUrl())
                .isEqualTo("http://cockpit.test:8080/operaton/app/cockpit/default/#/process-instance/" + instanceId);
        assertThat(PLUGIN.getStatistics().digestsSent("recording")).isPositive();
    }

    @Test
    void failedExternalTaskIncidentIsNotified() {
        EngineFixtures.deployExternalTaskProcess(processEngine, "ship");
        String instanceId = processEngine
                .getRuntimeService()
                .startProcessInstanceByKey("ship")
                .getId();

        EngineFixtures.failExternalTask(processEngine, instanceId);

        await().untilAsserted(() -> assertThat(CHANNEL.incidents()).hasSize(1));
        assertThat(CHANNEL.incidents().get(0).type()).isEqualTo("failedExternalTask");
        assertThat(CHANNEL.incidents().get(0).message()).contains("carrier rejected");
        assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
                .isEqualTo(1);
    }

    @Test
    void processVariablesSelectTheRecipients() {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-recipient");

        EngineFixtures.startAndFail(
                processEngine,
                "pay-recipient",
                null,
                Map.of("incidentNotificationReceiver", "a@example.org", "incidentNotificationCc", "b@example.org"));

        await().untilAsserted(() -> assertThat(CHANNEL.incidents()).hasSize(1));
        assertThat(CHANNEL.incidents().get(0).recipient()).isEqualTo("a@example.org");
        assertThat(CHANNEL.incidents().get(0).cc()).isEqualTo("b@example.org");
    }

    @Test
    void rolledBackIncidentIsNotNotified() {
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-rollback");
        String instanceId = processEngine
                .getRuntimeService()
                .startProcessInstanceByKey("pay-rollback")
                .getId();
        String executionId = processEngine
                .getRuntimeService()
                .createExecutionQuery()
                .processInstanceId(instanceId)
                .singleResult()
                .getId();
        long before = PLUGIN.getStatistics().incidentsObserved();

        Command<Void> createThenFail = ctx -> {
            processEngine.getRuntimeService().createIncident("failedJob", executionId, "cfg", "will be rolled back");
            throw new IllegalStateException("rollback");
        };
        try {
            ((org.operaton.bpm.engine.impl.ProcessEngineImpl) processEngine)
                    .getProcessEngineConfiguration()
                    .getCommandExecutorTxRequired()
                    .execute(createThenFail);
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("rollback");
        }

        assertThat(PLUGIN.getStatistics().incidentsObserved()).isEqualTo(before + 1);
        assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
                .isZero();
        assertThat(CHANNEL.digests).isEmpty();
    }

    @Test
    void failingChannelNeverAffectsTheEngineAndIsCounted() {
        CHANNEL.failing = true;
        EngineFixtures.deployFailingJobProcess(processEngine, "pay-down");
        long failedBefore = PLUGIN.getStatistics().digestsFailed("recording");

        EngineFixtures.startAndFail(processEngine, "pay-down", null, Map.of());

        assertThat(processEngine.getRuntimeService().createIncidentQuery().count())
                .isEqualTo(1);
        await().untilAsserted(() ->
                assertThat(PLUGIN.getStatistics().digestsFailed("recording")).isEqualTo(failedBefore + 1));
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.management.Metrics;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

class MicrometerMetricsPluginTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MicrometerMetricsPlugin plugin = new MicrometerMetricsPlugin(registry);

    @RegisterExtension
    final ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .configurator(cfg -> {
                plugin.setPollIntervalSeconds(1);
                plugin.setRunningInstancesEnabled(true);
                plugin.setMaxProcessDefinitionKeys(2);
                cfg.getProcessEnginePlugins().add(plugin);
            })
            .closeEngineAfterEachTest()
            .build();

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private double counterOrZero(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @Deployment(resources = "processes/wait-process.bpmn20.xml")
    void engineMetricsAreExposedAndSurviveTheDatabaseFlush() {
        ManagementService management = engine.getManagementService();
        management.reportDbMetricsNow();
        long before = management
                .createMetricsQuery()
                .name(Metrics.ACTIVTY_INSTANCE_START)
                .sum();

        engine.getRuntimeService().startProcessInstanceByKey("wait-process");
        double observed = registry.get("operaton.engine.activity.instance.start")
                .functionCounter()
                .count();
        assertThat(observed).isEqualTo(2); // start event + user task

        management.reportDbMetricsNow();
        long after = management
                .createMetricsQuery()
                .name(Metrics.ACTIVTY_INSTANCE_START)
                .sum();

        assertThat(after - before).isEqualTo(2);
        assertThat(registry.get("operaton.engine.activity.instance.start")
                        .functionCounter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.find("operaton.engine.job.acquisition.attempt").functionCounter())
                .isNotNull();
        assertThat(registry.get("operaton.engine.activity.instance.start")
                        .meter()
                        .getId()
                        .getTag("engine"))
                .isEqualTo("default");
    }

    @Test
    @Deployment(resources = "processes/wait-process.bpmn20.xml")
    void processInstanceStartAndEndAreCountedPerDefinitionKey() {
        ProcessInstance completed = engine.getRuntimeService().startProcessInstanceByKey("wait-process");
        engine.getTaskService()
                .complete(engine.getTaskService()
                        .createTaskQuery()
                        .processInstanceId(completed.getId())
                        .singleResult()
                        .getId());
        ProcessInstance cancelled = engine.getRuntimeService().startProcessInstanceByKey("wait-process");
        engine.getRuntimeService().deleteProcessInstance(cancelled.getId(), "test");

        assertThat(counter("operaton.process.instances.started", "process_definition_key", "wait-process"))
                .isEqualTo(2);
        assertThat(counter(
                        "operaton.process.instances.ended",
                        "process_definition_key",
                        "wait-process",
                        "state",
                        "completed"))
                .isEqualTo(1);
        assertThat(counter(
                        "operaton.process.instances.ended",
                        "process_definition_key",
                        "wait-process",
                        "state",
                        "internally_terminated"))
                .isEqualTo(1);
    }

    @Test
    @Deployment(resources = "processes/failing-start.bpmn20.xml")
    void aRolledBackTransactionIsNotCounted() {
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("failing-start"))
                .hasMessageContaining("boom");

        assertThat(counterOrZero("operaton.process.instances.started", "process_definition_key", "failing-start"))
                .isZero();
    }

    @Test
    @Deployment(resources = "processes/failing-job.bpmn20.xml")
    void failedJobsBecomeIncidentsAndAreCountedAndPolled() {
        engine.getRuntimeService().startProcessInstanceByKey("failing-job");
        Job job = engine.getManagementService().createJobQuery().singleResult();

        await().ignoreExceptions()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(gauge("operaton.jobs.due")).isEqualTo(1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> engine.getManagementService().executeJob(job.getId()))
                    .hasMessageContaining("boom");
        }
        assertThat(counter(
                        "operaton.incidents.raised",
                        "incident_type",
                        "failedJob",
                        "process_definition_key",
                        "failing-job"))
                .isEqualTo(1);
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(gauge("operaton.jobs.failed")).isEqualTo(1);
            assertThat(gauge("operaton.jobs.due")).isZero();
            assertThat(gauge("operaton.incidents.open", "incident_type", "failedJob"))
                    .isEqualTo(1);
            assertThat(gauge("operaton.incidents.open", "incident_type", "other"))
                    .isZero();
        });

        engine.getManagementService().setJobRetries(job.getId(), 1);

        assertThat(counter(
                        "operaton.incidents.resolved",
                        "incident_type",
                        "failedJob",
                        "process_definition_key",
                        "failing-job"))
                .isEqualTo(1);
        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(gauge("operaton.incidents.open", "incident_type", "failedJob"))
                    .isZero();
            assertThat(gauge("operaton.jobs.failed")).isZero();
        });
    }

    @Test
    @Deployment(
            resources = {
                "processes/wait-process.bpmn20.xml",
                "processes/other-wait-process.bpmn20.xml",
                "processes/failing-job.bpmn20.xml"
            })
    void runningInstancesArePolledAndKeysBeyondTheLimitAreFolded() {
        engine.getRuntimeService().startProcessInstanceByKey("wait-process");
        engine.getRuntimeService().startProcessInstanceByKey("wait-process");
        engine.getRuntimeService().startProcessInstanceByKey("other-wait-process");
        engine.getRuntimeService().startProcessInstanceByKey("failing-job");

        await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(gauge("operaton.process.instances.running", "process_definition_key", "wait-process"))
                    .isEqualTo(2);
            double other = registry
                    .find("operaton.process.instances.running")
                    .tags("process_definition_key", "other")
                    .gauges()
                    .stream()
                    .mapToDouble(g -> g.value())
                    .sum();
            assertThat(other).isEqualTo(1);
        });
        long distinct = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("operaton.process.instances.running"))
                .count();
        assertThat(distinct).isEqualTo(3); // two admitted keys plus 'other'
        assertThat(registry.getMeters().stream()
                        .map(m -> m.getId().getTag("process_definition_key"))
                        .filter(t -> t != null))
                .contains("other");
    }

    @Test
    void closeRemovesEveryMeter() {
        assertThat(registry.getMeters()).isNotEmpty();

        plugin.close();

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void pollGaugesShowTheLastSuccess() {
        assertThat(gauge("operaton.metrics.poll.last.success.seconds", "poller", "jobs"))
                .isGreaterThan(0);
        assertThat(counter("operaton.metrics.poll.errors", "poller", "jobs")).isZero();
    }

    private static void withEngine(
            ProcessEngineConfigurationImpl cfg, java.util.function.Consumer<ProcessEngine> body) {
        ProcessEngine pe = cfg.buildProcessEngine();
        try {
            body.accept(pe);
        } finally {
            pe.close();
        }
    }

    // ---- engines built by hand ---------------------------------------------------------------

    private static ProcessEngineConfigurationImpl config(String name, MicrometerMetricsPlugin plugin) {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName(name);
        cfg.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        return cfg;
    }

    @Test
    void anExplicitHistoryEventHandlerKeepsWorkingNextToTheCounters() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        List<HistoryEvent> seen = new ArrayList<>();
        ProcessEngineConfigurationImpl cfg = config("explicit-handler", new MicrometerMetricsPlugin(reg));
        cfg.setHistoryEventHandler(new HistoryEventHandler() {
            @Override
            public void handleEvent(HistoryEvent event) {
                seen.add(event);
            }

            @Override
            public void handleEvents(List<HistoryEvent> events) {
                seen.addAll(events);
            }
        });
        withEngine(cfg, pe -> {
            var deployment = pe.getRepositoryService()
                    .createDeployment()
                    .addClasspathResource("processes/wait-process.bpmn20.xml")
                    .deploy();
            pe.getRuntimeService().startProcessInstanceByKey("wait-process");

            assertThat(seen).isNotEmpty();
            assertThat(reg.get("operaton.process.instances.started")
                            .tag("engine", "explicit-handler")
                            .counter()
                            .count())
                    .isEqualTo(1);
            pe.getRepositoryService().deleteDeployment(deployment.getId(), true);
        });
    }

    @Test
    void disabledEngineMetricsSkipTheEngineMetersButNotTheRest() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ProcessEngineConfigurationImpl cfg = config("no-engine-metrics", new MicrometerMetricsPlugin(reg));
        cfg.setMetricsEnabled(false);
        withEngine(cfg, pe -> {
            assertThat(reg.getMeters().stream().filter(m -> m.getId().getName().startsWith("operaton.engine.")))
                    .isEmpty();
            assertThat(reg.find("operaton.jobs.due").gauge()).isNotNull();
        });
    }

    @Test
    void togglesTurnMetersOff() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MicrometerMetricsPlugin p = new MicrometerMetricsPlugin(reg);
        p.setEngineMetricsEnabled(false);
        p.setHistoryCountersEnabled(false);
        p.setJobBacklogEnabled(false);
        p.setIncidentsEnabled(false);
        withEngine(config("all-off", p), pe -> {
            assertThat(reg.getMeters().stream().map(Meter::getId)).isEmpty();
        });
    }

    @Test
    void invalidConfigurationRefusesToStartTheEngine() {
        MicrometerMetricsPlugin noRegistry = new MicrometerMetricsPlugin();
        assertThatThrownBy(() -> config("bad-1", noRegistry).buildProcessEngine())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MeterRegistry");

        MicrometerMetricsPlugin badInterval = new MicrometerMetricsPlugin(new SimpleMeterRegistry());
        badInterval.setPollIntervalSeconds(0);
        assertThatThrownBy(() -> config("bad-2", badInterval).buildProcessEngine())
                .hasMessageContaining("pollIntervalSeconds");
    }
}

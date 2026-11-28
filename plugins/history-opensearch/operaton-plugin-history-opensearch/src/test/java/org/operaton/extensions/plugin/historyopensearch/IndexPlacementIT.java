/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.count;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.hits;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.single;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

/**
 * Time-based indices: every event of one entity must update the one document, even when the entity
 * starts in one month and ends in the next. The engine clock is moved to simulate the passing time.
 */
@Timeout(300)
class IndexPlacementIT {

    private final java.util.List<ProcessEngine> engines = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        ClockUtil.reset();
        engines.forEach(ProcessEngine::close);
    }

    private static Date at(String instant) {
        return Date.from(java.time.Instant.parse(instant));
    }

    private ProcessEngine engine(HistoryOpenSearchPlugin plugin, String jdbcUrl) {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("it-" + UUID.randomUUID());
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        ProcessEngine engine = cfg.buildProcessEngine();
        engines.add(engine);
        return engine;
    }

    private ProcessEngine start(HistoryOpenSearchPlugin plugin) {
        ProcessEngine engine = engine(plugin, "jdbc:h2:mem:it" + UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        engine.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .deploy();
        return engine;
    }

    private static HistoryOpenSearchPlugin plugin(String prefix, boolean keepDatabaseHandler) {
        HistoryOpenSearchPlugin plugin = OpenSearchSupport.plugin(prefix);
        plugin.setSendMode(SendMode.SYNC);
        plugin.setKeepDatabaseHandler(keepDatabaseHandler);
        plugin.setIncludeVariableValues(true);
        return plugin;
    }

    @Test
    void anEntityThatSpansTwoMonthsStaysOneDocumentWithTheDatabaseHandler() {
        assertOneDocumentAcrossMonths(true);
    }

    @Test
    void anEntityThatSpansTwoMonthsStaysOneDocumentWithoutTheDatabaseHandler() {
        // without the database handler the end events carry no start time: the plugin has to look the document up
        assertOneDocumentAcrossMonths(false);
    }

    private void assertOneDocumentAcrossMonths(boolean keepDatabaseHandler) {
        String prefix = OpenSearchSupport.uniquePrefix();
        ProcessEngine engine = start(plugin(prefix, keepDatabaseHandler));
        ClockUtil.setCurrentTime(at("2026-01-31T23:59:00Z"));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getRuntimeService().setVariable(instance.getId(), "step", 1L);
        Task task = engine.getTaskService().createTaskQuery().singleResult();

        ClockUtil.setCurrentTime(at("2026-02-02T08:00:00Z"));
        engine.getRuntimeService().setVariable(instance.getId(), "step", 2L);
        engine.getTaskService().complete(task.getId());

        for (String kind : List.of("process-instances", "tasks", "variables")) {
            JsonNode all = hits(prefix + "-" + kind + "-*", "{\"match_all\":{}}");
            assertThat(all).as(kind).isNotEmpty();
            assertThat(all.findValues("_index"))
                    .as(kind + " indices")
                    .extracting(JsonNode::asText)
                    .containsOnly(prefix + "-" + kind + "-2026.01");
        }
        JsonNode process =
                single(prefix + "-process-instances-*", instance.getId()).path("_source");
        assertThat(process.path("startTime").asText()).startsWith("2026-01-31T23:59");
        assertThat(process.path("endTime").asText()).startsWith("2026-02-02T08:00");
        assertThat(process.path("state").asText()).isEqualTo("COMPLETED");
        // 32 h 1 min; the merge script derives it when the end event does not carry the start time
        assertThat(process.path("durationInMillis").asLong()).isEqualTo(((32L * 60) + 1) * 60 * 1000);
        JsonNode taskDoc = single(prefix + "-tasks-*", task.getId()).path("_source");
        assertThat(taskDoc.path("startTime").asText()).startsWith("2026-01-31T23:59");
        assertThat(taskDoc.path("endTime").asText()).startsWith("2026-02-02T08:00");
        // activities: the user task began in January and ended in February (one document, January); the end event began
        // in February
        List<JsonNode> activities = new java.util.ArrayList<>();
        hits(prefix + "-activity-instances-*", "{\"match_all\":{}}").forEach(activities::add);
        assertThat(activities).hasSize(3);
        JsonNode wait = activities.stream()
                .filter(a -> "wait".equals(a.path("_source").path("activityId").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(wait.path("_index").asText()).isEqualTo(prefix + "-activity-instances-2026.01");
        assertThat(wait.path("_source").path("endTime").asText()).startsWith("2026-02-02T08:00");
        JsonNode end = activities.stream()
                .filter(a -> "end".equals(a.path("_source").path("activityId").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(end.path("_index").asText()).isEqualTo(prefix + "-activity-instances-2026.02");
        assertThat(count(prefix + "-variables-*")).isEqualTo(1);
        assertThat(hits(prefix + "-variables-*", "{\"match_all\":{}}")
                        .get(0)
                        .path("_source")
                        .path("valueLong")
                        .asLong())
                .isEqualTo(2L);
    }

    @Test
    void aSecondNodeFindsADocumentThatIsNotSearchableYet() {
        // Two engines (nodes) share one database but not their memory. Node A creates the task document;
        // its index refreshes only on demand, so node B can see it only through the real-time get.
        String prefix = OpenSearchSupport.uniquePrefix();
        OpenSearchSupport.request(
                "PUT", "/" + prefix + "-tasks-2026.01", "{\"settings\":{\"index.refresh_interval\":\"-1\"}}");
        String jdbc = "jdbc:h2:mem:shared" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ProcessEngine nodeA = engine(plugin(prefix, false), jdbc);
        nodeA.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .deploy();
        ProcessEngine nodeB = engine(plugin(prefix, false), jdbc);

        ClockUtil.setCurrentTime(at("2026-01-31T23:59:58Z"));
        nodeA.getRuntimeService().startProcessInstanceByKey("simple");
        Task task = nodeB.getTaskService().createTaskQuery().singleResult();

        ClockUtil.setCurrentTime(at("2026-02-01T00:00:05Z"));
        nodeB.getTaskService().complete(task.getId());

        JsonNode doc = single(prefix + "-tasks-*", task.getId());
        assertThat(doc.path("_index").asText()).isEqualTo(prefix + "-tasks-2026.01");
        assertThat(doc.path("_source").path("startTime").asText()).startsWith("2026-01-31T23:59:58");
        assertThat(doc.path("_source").path("endTime").asText()).startsWith("2026-02-01T00:00:05");
        assertThat(doc.path("_source").path("durationInMillis").asLong()).isEqualTo(7_000L);
        assertThat(count(prefix + "-tasks-*")).isEqualTo(1);
    }

    @Test
    void anEntityTheIndexDoesNotKnowIsPlacedByTheCurrentTime() {
        // started before the plugin was installed: only its end event arrives
        String prefix = OpenSearchSupport.uniquePrefix();
        HistoryOpenSearchPlugin first = plugin(prefix, false);
        String jdbc = "jdbc:h2:mem:late" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        StandaloneInMemProcessEngineConfiguration plain = new StandaloneInMemProcessEngineConfiguration();
        plain.setProcessEngineName("plain-" + UUID.randomUUID());
        plain.setJdbcUrl(jdbc);
        plain.setDatabaseSchemaUpdate("true");
        plain.setJobExecutorActivate(false);
        plain.setHistory("full");
        ProcessEngine withoutPlugin = plain.buildProcessEngine();
        engines.add(withoutPlugin);
        withoutPlugin
                .getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .deploy();
        ClockUtil.setCurrentTime(at("2026-03-10T10:00:00Z"));
        withoutPlugin.getRuntimeService().startProcessInstanceByKey("simple");
        withoutPlugin.close();
        engines.remove(withoutPlugin);

        ProcessEngine withPlugin = engine(first, jdbc);
        ClockUtil.setCurrentTime(at("2026-04-02T10:00:00Z"));
        Task task = withPlugin.getTaskService().createTaskQuery().singleResult();
        withPlugin.getTaskService().complete(task.getId());

        JsonNode doc = single(prefix + "-tasks-*", task.getId());
        assertThat(doc.path("_index").asText()).isEqualTo(prefix + "-tasks-2026.04");
        assertThat(doc.path("_source").path("endTime").asText()).startsWith("2026-04-02T10:00");
    }

    @Test
    void theDatePatternDecidesTheGranularity() {
        String prefix = OpenSearchSupport.uniquePrefix();
        HistoryOpenSearchPlugin plugin = plugin(prefix, true);
        plugin.setIndexDatePattern("yyyy.MM.dd");
        ProcessEngine engine = start(plugin);
        ClockUtil.setCurrentTime(at("2026-05-06T10:00:00Z"));
        ProcessInstance first = engine.getRuntimeService().startProcessInstanceByKey("simple");
        ClockUtil.setCurrentTime(at("2026-05-07T10:00:00Z"));
        ProcessInstance second = engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(single(prefix + "-process-instances-*", first.getId())
                        .path("_index")
                        .asText())
                .isEqualTo(prefix + "-process-instances-2026.05.06");
        assertThat(single(prefix + "-process-instances-*", second.getId())
                        .path("_index")
                        .asText())
                .isEqualTo(prefix + "-process-instances-2026.05.07");
    }
}

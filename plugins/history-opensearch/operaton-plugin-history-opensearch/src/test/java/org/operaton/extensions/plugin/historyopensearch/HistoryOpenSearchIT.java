/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.count;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.hits;
import static org.operaton.extensions.plugin.historyopensearch.OpenSearchSupport.single;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;

/** Embedded engine (H2) plus a real OpenSearch 2.x: what is indexed after commit, and what is not. */
@Timeout(300)
class HistoryOpenSearchIT {

    private ProcessEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    private HistoryOpenSearchPlugin start(
            String prefix, java.util.function.Consumer<HistoryOpenSearchPlugin> customizer) {
        HistoryOpenSearchPlugin plugin = OpenSearchSupport.plugin(prefix);
        plugin.setSendMode(SendMode.SYNC);
        customizer.accept(plugin);
        engine = OpenSearchSupport.engine(plugin);
        return plugin;
    }

    @Test
    void startAndEndEventsAreMergedIntoOneDocumentPerEntity() {
        String prefix = OpenSearchSupport.uniquePrefix();
        HistoryOpenSearchPlugin plugin = start(prefix, p -> {});

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        Task task = engine.getTaskService().createTaskQuery().singleResult();
        engine.getTaskService().complete(task.getId());

        JsonNode process =
                single(prefix + "-process-instances-*", instance.getId()).path("_source");
        assertThat(process.path("processInstanceId").asText()).isEqualTo(instance.getId());
        assertThat(process.path("processDefinitionKey").asText()).isEqualTo("simple");
        assertThat(process.path("startTime").asText()).isNotBlank();
        assertThat(process.path("endTime").asText()).isNotBlank();
        assertThat(process.path("durationInMillis").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(process.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(process.path("lastEvent").asText()).isEqualTo("end");
        assertThat(count(prefix + "-process-instances-*")).isEqualTo(1);

        JsonNode taskDoc = single(prefix + "-tasks-*", task.getId()).path("_source");
        assertThat(taskDoc.path("name").asText()).isEqualTo("Wait");
        assertThat(taskDoc.path("assignee").asText()).isEqualTo("alice");
        assertThat(taskDoc.path("taskDefinitionKey").asText()).isEqualTo("wait");
        assertThat(taskDoc.path("startTime").asText()).isNotBlank();
        assertThat(taskDoc.path("endTime").asText()).isNotBlank();
        assertThat(taskDoc.path("durationInMillis").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(count(prefix + "-tasks-*")).isEqualTo(1);

        List<JsonNode> activities =
                hits(prefix + "-activity-instances-*", "{\"match_all\":{}}").findValues("_source");
        assertThat(activities)
                .extracting(a -> a.path("activityId").asText())
                .containsExactlyInAnyOrder("start", "wait", "end");
        assertThat(activities).allSatisfy(a -> {
            assertThat(a.path("processInstanceId").asText()).isEqualTo(instance.getId());
            assertThat(a.path("startTime").asText()).isNotBlank();
            assertThat(a.path("endTime").asText()).isNotBlank();
        });

        assertThat(plugin.getHandler().getFailedCount()).isZero();
        assertThat(plugin.getHandler().getIndexedCount()).isPositive();
    }

    @Test
    void nothingIsIndexedForARolledBackTransaction() {
        String prefix = OpenSearchSupport.uniquePrefix();
        HistoryOpenSearchPlugin plugin = start(prefix, p -> {});
        long discardedBefore = plugin.getHandler().getDiscardedCount();

        assertThatThrownBy(() -> ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                        .getCommandExecutorTxRequired()
                        .execute(context -> {
                            engine.getRuntimeService().startProcessInstanceByKey("simple");
                            throw new IllegalStateException("business failure");
                        }))
                .hasMessageContaining("business failure");
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("failing"))
                .hasMessageContaining("boom");

        assertThat(plugin.getHandler().getDiscardedCount()).isGreaterThan(discardedBefore);
        assertThat(plugin.getHandler().getIndexedCount()).isZero();
        assertThat(count(prefix + "-*")).isZero();

        // the engine keeps working and the next committed instance is indexed
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        assertThat(single(prefix + "-process-instances-*", instance.getId())
                        .path("_source")
                        .path("state")
                        .asText())
                .isEqualTo("ACTIVE");
        assertThat(count(prefix + "-process-instances-*")).isEqualTo(1);
    }

    @Test
    void asynchronousModeBatchesInTheBackgroundAndFlushWaitsForThem() {
        String prefix = OpenSearchSupport.uniquePrefix();
        HistoryOpenSearchPlugin plugin = start(prefix, p -> {
            p.setSendMode(SendMode.ASYNC);
            p.setFlushIntervalMillis(60_000); // only a full batch or an explicit flush writes
            p.setBatchSize(1_000);
        });

        for (int i = 0; i < 5; i++) {
            engine.getRuntimeService().startProcessInstanceByKey("simple");
        }
        assertThat(plugin.getHandler().getIndexedCount()).isZero();

        plugin.getHandler().flush(Duration.ofSeconds(30));

        assertThat(count(prefix + "-process-instances-*")).isEqualTo(5);
        assertThat(count(prefix + "-tasks-*")).isEqualTo(5);
        assertThat(plugin.getHandler().getFailedCount()).isZero();
    }

    @Test
    void asynchronousModeWritesWhenTheFlushIntervalPasses() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {
            p.setSendMode(SendMode.ASYNC);
            p.setFlushIntervalMillis(200);
        });

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(hits(
                                prefix + "-process-instances-*",
                                "{\"ids\":{\"values\":[\"" + instance.getId() + "\"]}}"))
                        .hasSize(1));
    }

    @Test
    void closingTheEngineWritesWhatIsStillQueued() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {
            p.setSendMode(SendMode.ASYNC);
            p.setFlushIntervalMillis(60_000);
        });
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");

        engine.close();
        engine = null;

        assertThat(single(prefix + "-process-instances-*", instance.getId())).isNotNull();
    }

    @Test
    void variablesAreIndexedByNameAndHoldTheCurrentValue() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> p.setIncludeVariableValues(true));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");

        engine.getRuntimeService().setVariable(instance.getId(), "amount", 100L);
        engine.getRuntimeService().setVariable(instance.getId(), "amount", 250L);
        engine.getRuntimeService().setVariable(instance.getId(), "customer", "ACME Corp");
        engine.getRuntimeService().setVariable(instance.getId(), "approved", true);
        engine.getRuntimeService().setVariable(instance.getId(), "ratio", 0.25d);
        engine.getRuntimeService().setVariable(instance.getId(), "doomed", "x");
        engine.getRuntimeService().removeVariable(instance.getId(), "doomed");

        List<JsonNode> variables =
                hits(prefix + "-variables-*", "{\"match_all\":{}}").findValues("_source");
        assertThat(variables)
                .extracting(v -> v.path("variableName").asText())
                .containsExactlyInAnyOrder("amount", "customer", "approved", "ratio", "doomed");
        JsonNode amount = byName(variables, "amount");
        assertThat(amount.path("valueLong").asLong()).isEqualTo(250L);
        assertThat(amount.path("variableType").asText()).isEqualToIgnoringCase("long");
        assertThat(amount.path("processInstanceId").asText()).isEqualTo(instance.getId());
        assertThat(amount.path("revision").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(byName(variables, "customer").path("valueText").asText()).isEqualTo("ACME Corp");
        assertThat(byName(variables, "approved").path("valueBoolean").asBoolean())
                .isTrue();
        assertThat(byName(variables, "ratio").path("valueDouble").asDouble()).isEqualTo(0.25d);
        assertThat(byName(variables, "doomed").path("state").asText()).isEqualTo("DELETED");
        assertThat(byName(variables, "amount").path("state").asText()).isEqualTo("CREATED");

        // searchable by value
        assertThat(
                        hits(
                                prefix + "-variables-*",
                                "{\"bool\":{\"filter\":[{\"term\":{\"variableName\":\"amount\"}},{\"range\":{\"valueLong\":{\"gte\":200}}}]}}"))
                .hasSize(1);
    }

    @Test
    void variableValuesAreNotIndexedByDefault() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {});
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getRuntimeService().setVariable(instance.getId(), "iban", "DE02120300000000202051");
        engine.getRuntimeService().setVariable(instance.getId(), "amount", 5L);

        List<JsonNode> variables =
                hits(prefix + "-variables-*", "{\"match_all\":{}}").findValues("_source");

        assertThat(variables)
                .extracting(v -> v.path("variableName").asText())
                .containsExactlyInAnyOrder("iban", "amount");
        assertThat(variables)
                .allSatisfy(v -> assertThat(v.fieldNames()).toIterable().noneMatch(name -> name.startsWith("value")));
        assertThat(hits(prefix + "-variables-*", "{\"query_string\":{\"query\":\"DE02120300000000202051\"}}"))
                .isEmpty();
    }

    @Test
    void longTextValuesAreCutAndFlagged() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {
            p.setIncludeVariableValues(true);
            p.setMaxVariableValueLength(10);
        });
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getRuntimeService().setVariable(instance.getId(), "note", "0123456789ABCDEF");

        JsonNode note =
                hits(prefix + "-variables-*", "{\"match_all\":{}}").get(0).path("_source");

        assertThat(note.path("valueText").asText()).isEqualTo("0123456789");
        assertThat(note.path("valueTruncated").asBoolean()).isTrue();
    }

    @Test
    void incidentsAreIndexedAndTrackTheirLifecycle() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {});
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        Incident incident =
                engine.getRuntimeService().createIncident("custom-type", instance.getId(), "cfg-1", "disk full");

        JsonNode open = single(prefix + "-incidents-*", incident.getId()).path("_source");
        assertThat(open.path("incidentType").asText()).isEqualTo("custom-type");
        assertThat(open.path("incidentMessage").asText()).isEqualTo("disk full");
        assertThat(open.path("open").asBoolean()).isTrue();
        assertThat(open.path("createTime").asText()).isNotBlank();
        assertThat(open.path("processInstanceId").asText()).isEqualTo(instance.getId());

        engine.getRuntimeService().resolveIncident(incident.getId());

        JsonNode resolved = single(prefix + "-incidents-*", incident.getId()).path("_source");
        assertThat(resolved.path("resolved").asBoolean()).isTrue();
        assertThat(resolved.path("open").asBoolean()).isFalse();
        assertThat(resolved.path("createTime").asText())
                .isEqualTo(open.path("createTime").asText());
        assertThat(resolved.path("endTime").asText()).isNotBlank();
        assertThat(count(prefix + "-incidents-*")).isEqualTo(1);
    }

    @Test
    void eventTypeFilterRestrictsWhatIsIndexed() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> p.setEventTypes(List.of("process-instance.end", "task-instance")));

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getTaskService()
                .complete(
                        engine.getTaskService().createTaskQuery().singleResult().getId());

        assertThat(count(prefix + "-activity-instances-*")).isZero();
        assertThat(count(prefix + "-variables-*")).isZero();
        assertThat(count(prefix + "-tasks-*")).isEqualTo(1);
        JsonNode process =
                single(prefix + "-process-instances-*", instance.getId()).path("_source");
        assertThat(process.path("endTime").asText()).isNotBlank();
        assertThat(process.path("lastEvent").asText()).isEqualTo("end");
    }

    @Test
    void indexTemplatesAreInstalledAtStartupAndInstallingThemAgainIsHarmless() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> p.setNumberOfShards(1));
        engine.close();
        engine = null;
        // a second engine (and plugin) against the same cluster: the templates are replaced by the same definition
        start(prefix, p -> p.setNumberOfShards(1));

        for (String kind : List.of("process-instances", "activity-instances", "tasks", "variables", "incidents")) {
            JsonNode template = OpenSearchSupport.request("GET", "/_index_template/" + prefix + "-" + kind, null)
                    .path("index_templates")
                    .get(0)
                    .path("index_template");
            assertThat(template.path("index_patterns"))
                    .extracting(JsonNode::asText)
                    .contains(prefix + "-" + kind + "-*");
            assertThat(template.path("version").asInt()).isEqualTo(1);
        }
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        JsonNode hit = single(prefix + "-process-instances-*", instance.getId());
        JsonNode mapping = OpenSearchSupport.request(
                        "GET", "/" + hit.path("_index").asText() + "/_mapping", null)
                .path(hit.path("_index").asText())
                .path("mappings")
                .path("properties");
        assertThat(mapping.path("startTime").path("type").asText()).isEqualTo("date");
        assertThat(mapping.path("durationInMillis").path("type").asText()).isEqualTo("long");
        assertThat(mapping.path("processDefinitionKey").path("type").asText()).isEqualTo("keyword");
        assertThat(OpenSearchSupport.request("GET", "/" + hit.path("_index").asText() + "/_settings", null)
                        .path(hit.path("_index").asText())
                        .path("settings")
                        .path("index")
                        .path("number_of_shards")
                        .asText())
                .isEqualTo("1");
    }

    @Test
    void indicesAreNamedByPrefixAndMonth() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> {});
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");

        String index = single(prefix + "-process-instances-*", instance.getId())
                .path("_index")
                .asText();

        assertThat(index).matches(prefix + "-process-instances-\\d{4}\\.\\d{2}");
    }

    @Test
    void documentsGoToASingleIndexWhenTheDatePatternIsEmpty() {
        String prefix = OpenSearchSupport.uniquePrefix();
        start(prefix, p -> p.setIndexDatePattern(""));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getTaskService()
                .complete(
                        engine.getTaskService().createTaskQuery().singleResult().getId());

        JsonNode hit = single(prefix + "-process-instances", instance.getId());

        assertThat(hit.path("_index").asText()).isEqualTo(prefix + "-process-instances");
        assertThat(hit.path("_source").path("state").asText()).isEqualTo("COMPLETED");
    }

    private static JsonNode byName(List<JsonNode> variables, String name) {
        return variables.stream()
                .filter(v -> name.equals(v.path("variableName").asText()))
                .findFirst()
                .orElseThrow();
    }
}

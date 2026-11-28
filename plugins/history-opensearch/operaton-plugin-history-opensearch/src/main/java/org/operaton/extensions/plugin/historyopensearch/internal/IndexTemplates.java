/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchOptions;

/**
 * Builds the composable index templates (one per category) that give the history indices their
 * mappings and settings. The request for a template is always the same for the same options, so
 * installing it again is harmless (idempotent). Immutable and thread-safe.
 */
public final class IndexTemplates {

    /** Bumped whenever the mappings change; stored in the template's {@code version}. */
    static final int VERSION = 1;

    private static final String DATE = "date";
    private static final String KEYWORD = "keyword";
    private static final String LONG = "long";
    private static final String INTEGER = "integer";
    private static final String BOOLEAN = "boolean";
    private static final String TEXT = "text";

    private final ObjectMapper json;
    private final HistoryOpenSearchOptions options;
    private final IndexNames names;

    /** Creates the builder for the given options. */
    public IndexTemplates(ObjectMapper json, HistoryOpenSearchOptions options) {
        this.json = json;
        this.options = options;
        this.names = options.indexNames();
    }

    /** The template name and request body per category, in a stable order. */
    public Map<String, ObjectNode> all() {
        Map<String, ObjectNode> templates = new LinkedHashMap<>();
        for (String category : List.of(
                HistoryEventCategories.PROCESS_INSTANCE,
                HistoryEventCategories.ACTIVITY_INSTANCE,
                HistoryEventCategories.TASK_INSTANCE,
                HistoryEventCategories.VARIABLE_INSTANCE,
                HistoryEventCategories.INCIDENT)) {
            templates.put(names.base(category), template(category));
        }
        return templates;
    }

    /** The request body of the template of one category. */
    public ObjectNode template(String category) {
        ObjectNode root = json.createObjectNode();
        ArrayNode patterns = root.putArray("index_patterns");
        names.templatePatterns(category).forEach(patterns::add);
        root.put("priority", 100);
        root.put("version", VERSION);
        ObjectNode template = root.putObject("template");
        ObjectNode settings = template.putObject("settings");
        if (options.numberOfShards() != null) {
            settings.put("index.number_of_shards", options.numberOfShards());
        }
        if (options.numberOfReplicas() != null) {
            settings.put("index.number_of_replicas", options.numberOfReplicas());
        }
        ObjectNode mappings = template.putObject("mappings");
        mappings.putObject("_meta")
                .put("managed-by", "operaton-plugin-history-opensearch")
                .put("category", category);
        ObjectNode strings = json.createObjectNode();
        strings.putObject("strings_as_keywords")
                .put("match_mapping_type", "string")
                .putObject("mapping")
                .put("type", KEYWORD)
                .put("ignore_above", 1024);
        mappings.putArray("dynamic_templates").add(strings);
        ObjectNode properties = mappings.putObject("properties");
        common(properties);
        switch (category) {
            case HistoryEventCategories.PROCESS_INSTANCE -> processInstance(properties);
            case HistoryEventCategories.ACTIVITY_INSTANCE -> activityInstance(properties);
            case HistoryEventCategories.TASK_INSTANCE -> task(properties);
            case HistoryEventCategories.VARIABLE_INSTANCE -> variable(properties);
            case HistoryEventCategories.INCIDENT -> incident(properties);
            default -> throw new IllegalArgumentException("Unknown history category " + category);
        }
        return root;
    }

    private void common(ObjectNode p) {
        keyword(p, "processInstanceId", "rootProcessInstanceId", "processDefinitionId", "processDefinitionKey");
        keyword(p, "executionId", "tenantId", "lastEvent");
        textWithKeyword(p, "processDefinitionName");
        p.putObject("processDefinitionVersion").put("type", INTEGER);
        date(p, "removalTime", "updatedAt");
    }

    private void processInstance(ObjectNode p) {
        date(p, "startTime", "endTime");
        p.putObject("durationInMillis").put("type", LONG);
        keyword(p, "businessKey", "startUserId", "startActivityId", "endActivityId", "superProcessInstanceId");
        keyword(p, "state", "restartedProcessInstanceId");
        textWithKeyword(p, "deleteReason");
    }

    private void activityInstance(ObjectNode p) {
        date(p, "startTime", "endTime");
        p.putObject("durationInMillis").put("type", LONG);
        keyword(p, "activityInstanceId", "activityId", "activityType", "parentActivityInstanceId");
        keyword(p, "calledProcessInstanceId", "taskId", "taskAssignee", "assignee");
        textWithKeyword(p, "activityName");
        p.putObject("activityInstanceState").put("type", INTEGER);
        p.putObject("completeScope").put("type", BOOLEAN);
        p.putObject("canceled").put("type", BOOLEAN);
    }

    private void task(ObjectNode p) {
        date(p, "startTime", "endTime", "dueDate", "followUpDate");
        p.putObject("durationInMillis").put("type", LONG);
        keyword(p, "taskId", "taskDefinitionKey", "assignee", "owner", "parentTaskId", "activityInstanceId");
        keyword(p, "taskState");
        textWithKeyword(p, "name");
        textWithKeyword(p, "deleteReason");
        p.putObject("description").put("type", TEXT);
        p.putObject("priority").put("type", INTEGER);
    }

    private void variable(ObjectNode p) {
        date(p, "timestamp", "valueDate");
        keyword(p, "variableInstanceId", "variableName", "variableType", "scopeActivityInstanceId");
        keyword(p, "activityInstanceId", "taskId", "state");
        p.putObject("revision").put("type", INTEGER);
        ObjectNode text = p.putObject("valueText");
        text.put("type", TEXT);
        text.putObject("fields").putObject("raw").put("type", KEYWORD).put("ignore_above", 1024);
        p.putObject("valueLong").put("type", LONG);
        p.putObject("valueDouble").put("type", "double");
        p.putObject("valueBoolean").put("type", BOOLEAN);
        p.putObject("valueTruncated").put("type", BOOLEAN);
    }

    private void incident(ObjectNode p) {
        date(p, "createTime", "endTime");
        keyword(p, "incidentType", "activityId", "failedActivityId", "causeIncidentId", "rootCauseIncidentId");
        keyword(p, "configuration", "jobDefinitionId");
        p.putObject("incidentMessage").put("type", TEXT);
        p.putObject("annotation").put("type", TEXT);
        p.putObject("open").put("type", BOOLEAN);
        p.putObject("deleted").put("type", BOOLEAN);
        p.putObject("resolved").put("type", BOOLEAN);
    }

    private static void keyword(ObjectNode properties, String... fields) {
        for (String field : fields) {
            properties.putObject(field).put("type", KEYWORD);
        }
    }

    private static void date(ObjectNode properties, String... fields) {
        for (String field : fields) {
            properties.putObject(field).put("type", DATE).put("format", "strict_date_optional_time||epoch_millis");
        }
    }

    private static void textWithKeyword(ObjectNode properties, String field) {
        ObjectNode node = properties.putObject(field);
        node.put("type", TEXT);
        node.putObject("fields").putObject("raw").put("type", KEYWORD).put("ignore_above", 1024);
    }
}

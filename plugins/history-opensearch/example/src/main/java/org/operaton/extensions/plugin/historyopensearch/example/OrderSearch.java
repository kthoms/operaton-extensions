/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Answers questions about orders from the OpenSearch indices that the history plugin fills: this is
 * the "searchable history" the plugin exists for. One query per question, plain REST.
 */
@Service
public class OrderSearch {

    /** One order as found in the index. {@code amount} comes from the order's {@code amount} variable. */
    public record OrderView(
            String processInstanceId,
            String state,
            String startTime,
            String endTime,
            Long durationInMillis,
            Long amount) {}

    /** Orders per state and how long finished orders took on average. */
    public record Summary(long total, Map<String, Long> byState, Double averageDurationMillis) {}

    /** A task of an order that nobody has completed yet. */
    public record OpenTask(String taskId, String name, String processInstanceId, String startTime) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String prefix;

    OrderSearch(
            @Value("${operaton.extensions.plugin.history-opensearch.uris[0]:http://localhost:9200}") String baseUrl,
            @Value("${operaton.extensions.plugin.history-opensearch.index-prefix:operaton}") String prefix) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.prefix = prefix;
    }

    /** The orders of a customer (the {@code customer} variable), newest first. */
    public List<OrderView> findByCustomer(String customer) {
        JsonNode customerVariables = search(
                "variables",
                "{\"size\":100,\"_source\":[\"processInstanceId\"],\"query\":{\"bool\":{\"filter\":["
                        + "{\"term\":{\"variableName\":\"customer\"}},"
                        + "{\"term\":{\"valueText.raw\":" + quote(customer) + "}}]}}}");
        List<String> ids = new ArrayList<>();
        customerVariables
                .path("hits")
                .path("hits")
                .forEach(hit ->
                        ids.add(hit.path("_source").path("processInstanceId").asText()));
        if (ids.isEmpty()) {
            return List.of();
        }
        String idList = JSON.valueToTree(ids).toString();
        Map<String, Long> amounts = new LinkedHashMap<>();
        search(
                        "variables",
                        "{\"size\":100,\"_source\":[\"processInstanceId\",\"valueLong\"],\"query\":{\"bool\":{\"filter\":["
                                + "{\"term\":{\"variableName\":\"amount\"}},{\"terms\":{\"processInstanceId\":" + idList
                                + "}}]}}}")
                .path("hits")
                .path("hits")
                .forEach(hit -> amounts.put(
                        hit.path("_source").path("processInstanceId").asText(),
                        hit.path("_source").path("valueLong").asLong()));
        List<OrderView> orders = new ArrayList<>();
        search(
                        "process-instances",
                        "{\"size\":100,\"sort\":[{\"startTime\":\"desc\"}],\"query\":{\"terms\":{\"processInstanceId\":"
                                + idList + "}}}")
                .path("hits")
                .path("hits")
                .forEach(hit -> {
                    JsonNode order = hit.path("_source");
                    String id = order.path("processInstanceId").asText();
                    orders.add(new OrderView(
                            id,
                            order.path("state").asText(null),
                            order.path("startTime").asText(null),
                            order.path("endTime").asText(null),
                            order.has("durationInMillis")
                                    ? order.path("durationInMillis").asLong()
                                    : null,
                            amounts.get(id)));
                });
        return orders;
    }

    /** All orders by state, and the average duration of the finished ones. */
    public Summary summary() {
        JsonNode result = search(
                "process-instances",
                "{\"size\":0,\"track_total_hits\":true,\"query\":{\"term\":{\"processDefinitionKey\":\"order-fulfilment\"}},"
                        + "\"aggs\":{\"by_state\":{\"terms\":{\"field\":\"state\"}},\"avg_duration\":{\"avg\":{\"field\":\"durationInMillis\"}}}}");
        Map<String, Long> byState = new LinkedHashMap<>();
        result.path("aggregations")
                .path("by_state")
                .path("buckets")
                .forEach(bucket -> byState.put(
                        bucket.path("key").asText(), bucket.path("doc_count").asLong()));
        JsonNode average = result.path("aggregations").path("avg_duration").path("value");
        return new Summary(
                result.path("hits").path("total").path("value").asLong(),
                byState,
                average.isNull() ? null : average.asDouble());
    }

    /** Order tasks that have no end time yet. */
    public List<OpenTask> openTasks() {
        List<OpenTask> tasks = new ArrayList<>();
        search(
                        "tasks",
                        "{\"size\":100,\"sort\":[{\"startTime\":\"asc\"}],\"query\":{\"bool\":{\"filter\":"
                                + "[{\"term\":{\"processDefinitionKey\":\"order-fulfilment\"}}],"
                                + "\"must_not\":[{\"exists\":{\"field\":\"endTime\"}}]}}}")
                .path("hits")
                .path("hits")
                .forEach(hit -> {
                    JsonNode task = hit.path("_source");
                    tasks.add(new OpenTask(
                            task.path("taskId").asText(),
                            task.path("name").asText(),
                            task.path("processInstanceId").asText(),
                            task.path("startTime").asText(null)));
                });
        return tasks;
    }

    private JsonNode search(String kind, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + prefix + "-" + kind
                        + "-*/_search?ignore_unavailable=true&allow_no_indices=true"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("OpenSearch search in " + kind + " failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot search OpenSearch (" + kind + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while searching OpenSearch (" + kind + ")", e);
        }
    }

    private static String quote(String value) {
        return JSON.valueToTree(value).toString();
    }
}

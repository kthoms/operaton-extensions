/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One real single-node OpenSearch 2.x (security disabled) shared by the integration tests of the JVM,
 * plus an independent REST helper to look at what the plugin indexed. The container is stopped by
 * Testcontainers' resource reaper when the JVM exits.
 */
final class OpenSearchSupport {

    static final String IMAGE = "opensearchproject/opensearch:2.19.1";
    static final ObjectMapper JSON = new ObjectMapper();

    /** Created on first use, so tests that do not need OpenSearch never touch Docker. */
    private static final class Holder {
        static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(IMAGE)
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=30s")
                        .forPort(9200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private OpenSearchSupport() {}

    /** The base URL of the shared container; starts it on first use. */
    static synchronized String url() {
        if (!Holder.OPENSEARCH.isRunning()) {
            Holder.OPENSEARCH.start();
        }
        return "http://" + Holder.OPENSEARCH.getHost() + ":" + Holder.OPENSEARCH.getMappedPort(9200);
    }

    /** A plugin pointing at the container; each test uses its own index prefix so tests never see each other's data. */
    static HistoryOpenSearchPlugin plugin(String prefix) {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        plugin.setUris(java.util.List.of(url()));
        plugin.setIndexPrefix(prefix);
        plugin.setFlushIntervalMillis(100);
        return plugin;
    }

    static String uniquePrefix() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** An in-memory engine with the plugin and the test processes deployed. */
    static ProcessEngine engine(HistoryOpenSearchPlugin plugin) {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("it-" + UUID.randomUUID());
        cfg.setJdbcUrl("jdbc:h2:mem:it" + UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        ProcessEngine engine = cfg.buildProcessEngine();
        engine.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .addClasspathResource("bpmn/failing.bpmn")
                .deploy();
        return engine;
    }

    /** Sends a request; a 503 (shards of a fresh index not started yet) is tried again for a few seconds. */
    static JsonNode request(String method, String path, String body) {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url() + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 503 && attempt < 25) {
                    Thread.sleep(200);
                    continue;
                }
                if (response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            method + " " + path + " -> " + response.statusCode() + " " + response.body());
                }
                return JSON.readTree(response.body());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** Refreshes the matching indices, so that everything written so far is searchable. */
    static void refresh(String indexPattern) {
        request("POST", "/" + indexPattern + "/_refresh?ignore_unavailable=true&allow_no_indices=true", null);
    }

    /** All documents (id, index, source) of the matching indices, after a refresh. */
    static JsonNode search(String indexPattern, String queryJson) {
        refresh(indexPattern);
        return request(
                "POST",
                "/" + indexPattern + "/_search?ignore_unavailable=true&allow_no_indices=true",
                "{\"size\":1000,\"query\":" + queryJson + "}");
    }

    static JsonNode hits(String indexPattern, String queryJson) {
        return search(indexPattern, queryJson).path("hits").path("hits");
    }

    static long count(String indexPattern) {
        return hits(indexPattern, "{\"match_all\":{}}").size();
    }

    /** The single document with this id in the matching indices; fails when there is none or more than one. */
    static JsonNode single(String indexPattern, String id) {
        JsonNode hits = hits(indexPattern, "{\"ids\":{\"values\":[\"" + id + "\"]}}");
        if (hits.size() != 1) {
            throw new AssertionError(
                    "expected exactly one document " + id + " in " + indexPattern + " but found " + hits);
        }
        return hits.get(0);
    }
}

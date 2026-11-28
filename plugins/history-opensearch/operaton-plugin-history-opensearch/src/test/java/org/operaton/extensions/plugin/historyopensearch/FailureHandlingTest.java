/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.ProcessInstance;

/**
 * Failure policy, retries with back-off, authentication headers and startup behaviour, against a scripted
 * fault-injecting stub (a healthy real OpenSearch cannot be told to fail on demand).
 */
class FailureHandlingTest {

    private StubOpenSearch stub;
    private ProcessEngine engine;

    @BeforeEach
    void startStub() throws IOException {
        stub = new StubOpenSearch();
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
        stub.close();
    }

    private HistoryOpenSearchPlugin plugin(String url) {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        plugin.setUris(List.of(url));
        plugin.setSendMode(SendMode.SYNC);
        plugin.setInitialBackoffMillis(5);
        plugin.setMaxBackoffMillis(20);
        plugin.setSendTimeoutMillis(5_000);
        return plugin;
    }

    private HistoryOpenSearchPlugin start(java.util.function.Consumer<HistoryOpenSearchPlugin> customizer) {
        HistoryOpenSearchPlugin plugin = plugin(stub.url());
        customizer.accept(plugin);
        engine = OpenSearchSupport.engine(plugin);
        return plugin;
    }

    @Test
    void aFailedRequestIsRetriedWithBackoffAndThenSucceeds() {
        HistoryOpenSearchPlugin plugin = start(p -> {});
        stub.onNextBulk(r -> StubOpenSearch.status(503));
        stub.onNextBulk(r -> StubOpenSearch.status(429));

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.bulkRequests()).hasSize(3);
        assertThat(plugin.getHandler().getRetryCount()).isEqualTo(2);
        assertThat(plugin.getHandler().getFailedCount()).isZero();
        assertThat(plugin.getHandler().getIndexedCount()).isPositive();
    }

    @Test
    void onlyTheRejectedDocumentsAreSentAgain() {
        HistoryOpenSearchPlugin plugin = start(p -> {});
        stub.onNextBulk(StubOpenSearch.itemFails(0, 429, "es_rejected_execution_exception"));

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        List<StubOpenSearch.Request> bulks = stub.bulkRequests();
        assertThat(bulks).hasSize(2);
        int first = bulks.get(0).body().split("\n").length / 2;
        int second = bulks.get(1).body().split("\n").length / 2;
        assertThat(first).isGreaterThan(1);
        assertThat(second).isEqualTo(1);
        assertThat(plugin.getHandler().getFailedCount()).isZero();
        assertThat(plugin.getHandler().getIndexedCount()).isEqualTo(first);
    }

    @Test
    void retriesStopAtTheLimitAndTheFailurePolicyFailReportsToTheCaller() {
        HistoryOpenSearchPlugin plugin = start(p -> p.setMaxRetries(2));
        for (int i = 0; i < 3; i++) {
            stub.onNextBulk(r -> StubOpenSearch.status(503));
        }

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("simple"))
                .isInstanceOf(HistoryOpenSearchException.class)
                .hasMessageContaining("after the engine transaction committed")
                .hasMessageContaining("NOT rolled back")
                .hasMessageContaining("HTTP 503");

        assertThat(stub.bulkRequests()).hasSize(3);
        assertThat(plugin.getHandler().getFailedCount()).isPositive();
        // the engine transaction was committed and is not undone
        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aPermanentRejectionIsNotRetriedAndNamesTheDocumentButNotItsFields() {
        HistoryOpenSearchPlugin plugin = start(p -> p.setIncludeVariableValues(true));
        stub.onNextBulk(StubOpenSearch.itemFails(0, 400, "mapper_parsing_exception"));

        assertThatThrownBy(() -> engine.getRuntimeService()
                        .startProcessInstanceByKey("simple", java.util.Map.of("secret", "s3cr3t-value")))
                .isInstanceOf(HistoryOpenSearchException.class)
                .hasMessageContaining("mapper_parsing_exception")
                .hasMessageContaining("category=")
                .satisfies(e -> assertThat(String.valueOf(e) + e.getCause()).doesNotContain("s3cr3t-value"));

        assertThat(stub.bulkRequests()).hasSize(1);
        assertThat(plugin.getHandler().getRetryCount()).isZero();
        assertThat(plugin.getHandler().getFailedCount()).isEqualTo(1);
    }

    @Test
    void logAndCountKeepsTheCallerOutOfIt() {
        HistoryOpenSearchPlugin plugin = start(p -> {
            p.setFailurePolicy(FailurePolicy.LOG_AND_COUNT);
            p.setMaxRetries(0);
        });
        stub.onNextBulk(r -> StubOpenSearch.status(500));

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(instance).isNotNull();
        assertThat(plugin.getHandler().getFailedCount()).isPositive();
        // the next transaction is written normally
        long indexed = plugin.getHandler().getIndexedCount();
        engine.getRuntimeService().startProcessInstanceByKey("simple");
        assertThat(plugin.getHandler().getIndexedCount()).isGreaterThan(indexed);
    }

    @Test
    void asynchronousWriteErrorsAreCountedNotThrown() {
        HistoryOpenSearchPlugin plugin = start(p -> {
            p.setSendMode(SendMode.ASYNC);
            p.setMaxRetries(0);
        });
        stub.onNextBulk(r -> StubOpenSearch.status(500));

        engine.getRuntimeService().startProcessInstanceByKey("simple");
        plugin.getHandler().flush(Duration.ofSeconds(10));

        assertThat(plugin.getHandler().getFailedCount()).isPositive();
        assertThat(plugin.getHandler().getIndexedCount()).isZero();
    }

    @Test
    void anUnreachableClusterFailsEngineStartupWithTheFailPolicy() {
        HistoryOpenSearchPlugin plugin = plugin("http://127.0.0.1:1");
        plugin.setMaxRetries(1);

        assertThatThrownBy(() -> OpenSearchSupport.engine(plugin))
                .hasStackTraceContaining("cannot install the index templates");
    }

    @Test
    void anUnreachableClusterDoesNotStopTheEngineWithLogAndCountAndTemplatesAreInstalledLater() {
        HistoryOpenSearchPlugin unreachable = plugin("http://127.0.0.1:1");
        unreachable.setFailurePolicy(FailurePolicy.LOG_AND_COUNT);
        unreachable.setMaxRetries(0);
        engine = OpenSearchSupport.engine(unreachable);

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(unreachable.getHandler().getFailedCount()).isPositive();
        assertThat(unreachable.getHandler().getIndexedCount()).isZero();
    }

    @Test
    void templatesAreInstalledOnceAtStartup() {
        start(p -> {});
        List<String> templates = stub.requests().stream()
                .filter(r -> r.method().equals("PUT"))
                .map(StubOpenSearch.Request::path)
                .toList();
        assertThat(templates)
                .containsExactly(
                        "/_index_template/operaton-process-instances",
                        "/_index_template/operaton-activity-instances",
                        "/_index_template/operaton-tasks",
                        "/_index_template/operaton-variables",
                        "/_index_template/operaton-incidents");
    }

    @Test
    void templatesAreLeftAloneWhenTheyAreManagedElsewhere() {
        start(p -> p.setManageTemplates(false));

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.requests()).isNotEmpty().noneMatch(r -> r.method().equals("PUT"));
    }

    @Test
    void basicAuthenticationAndTheNdjsonContentTypeAreSent() {
        start(p -> {
            p.setUsername("indexer");
            p.setPassword("s3cret");
        });

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        String expected = "Basic " + Base64.getEncoder().encodeToString("indexer:s3cret".getBytes());
        assertThat(stub.requests())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.headers().get("authorization")).isEqualTo(expected));
        assertThat(stub.bulkRequests().get(0).headers().get("content-type")).startsWith("application/x-ndjson");
    }

    @Test
    void anApiKeyIsSentAsApiKeyAuthorization() {
        start(p -> p.setApiKey("aWQ6c2VjcmV0"));

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.requests())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.headers().get("authorization")).isEqualTo("ApiKey aWQ6c2VjcmV0"));
    }

    @Test
    void theBulkRequestCarriesScriptedUpsertsWithoutVariableValuesByDefault() {
        start(p -> {});

        engine.getRuntimeService()
                .startProcessInstanceByKey("simple", java.util.Map.of("iban", "DE02120300000000202051"));

        String body = stub.bulkRequests().get(0).body();
        assertThat(body)
                .contains("\"update\"")
                .contains("\"scripted_upsert\":true")
                .contains("\"painless\"");
        assertThat(body).contains("\"_index\":\"operaton-process-instances-").doesNotContain("DE02120300000000202051");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

class HistoryOpenSearchPluginConfigurationTest {

    private static HistoryOpenSearchOptions validate(Consumer<HistoryOpenSearchPlugin> customizer) {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        customizer.accept(plugin);
        return plugin.validate();
    }

    private static void assertInvalid(Consumer<HistoryOpenSearchPlugin> customizer, String message) {
        assertThatThrownBy(() -> validate(customizer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history-opensearch")
                .hasMessageContaining(message);
    }

    @Test
    void defaultsAreSensible() {
        HistoryOpenSearchOptions options = validate(p -> {});

        assertThat(options.connection().uris()).extracting(Object::toString).containsExactly("http://localhost:9200");
        assertThat(options.indexPrefix()).isEqualTo("operaton");
        assertThat(options.indexDatePattern()).isEqualTo("yyyy.MM");
        assertThat(options.indexNames().at("process-instance", java.time.Instant.parse("2026-11-28T10:00:00Z")))
                .isEqualTo("operaton-process-instances-2026.11");
        assertThat(options.failurePolicy()).isEqualTo(FailurePolicy.FAIL);
        assertThat(options.sendMode()).isEqualTo(SendMode.ASYNC);
        assertThat(options.includeVariableValues()).isFalse();
        assertThat(options.manageTemplates()).isTrue();
        assertThat(options.eventTypes()).isEmpty();
        assertThat(options.batchSize()).isEqualTo(500);
        assertThat(options.flushInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.numberOfShards()).isNull();
    }

    @Test
    void rejectsInvalidConnectionSettings() {
        assertInvalid(p -> p.setUris(List.of()), "at least one OpenSearch URI");
        assertInvalid(p -> p.setUris(List.of("localhost:9200")), "invalid OpenSearch URI");
        assertInvalid(p -> p.setUris(List.of("ftp://localhost:9200")), "invalid OpenSearch URI");
        assertInvalid(p -> p.setUris(List.of("http://user:pw@localhost:9200")), "must not be part of the URI");
        assertInvalid(
                p -> {
                    p.setUsername("u");
                    p.setPassword("p");
                    p.setApiKey("k");
                },
                "either username/password or apiKey");
        assertInvalid(p -> p.setUsername("u"), "'password' is not");
        assertInvalid(p -> p.setPassword("p"), "'username' is not");
        assertInvalid(p -> p.setConnectTimeoutMillis(0), "connectTimeout");
        assertInvalid(p -> p.setRequestTimeoutMillis(-1), "requestTimeout");
    }

    @Test
    void rejectsInvalidIndexSettings() {
        assertInvalid(p -> p.setIndexPrefix("Upper"), "indexPrefix");
        assertInvalid(p -> p.setIndexPrefix(""), "indexPrefix");
        assertInvalid(p -> p.setIndexPrefix("-lead"), "indexPrefix");
        assertInvalid(p -> p.setIndexDatePattern("yyyy/MM"), "not a valid index name part");
        assertInvalid(
                p -> p.setIndexDatePattern("MMM"), "not a valid index name part"); // 'Nov' has an upper case letter
        assertInvalid(p -> p.setIndexDatePattern("yyyy.QQQQQQ"), "invalid indexDatePattern");
        assertInvalid(p -> p.setNumberOfShards(0), "numberOfShards");
        assertInvalid(p -> p.setNumberOfReplicas(-1), "numberOfReplicas");
    }

    @Test
    void acceptsDailyOrNoDatePattern() {
        assertThat(validate(p -> p.setIndexDatePattern("yyyy.MM.dd")).indexDatePattern())
                .isEqualTo("yyyy.MM.dd");
        assertThat(validate(p -> p.setIndexDatePattern("")).indexNames().timeBased())
                .isFalse();
        assertThat(validate(p -> p.setIndexPrefix("acme-prod.bpm")).indexNames().base("incident"))
                .isEqualTo("acme-prod.bpm-incidents");
    }

    @Test
    void rejectsInvalidDeliverySettings() {
        assertInvalid(p -> p.setFailurePolicy(null), "failurePolicy");
        assertInvalid(p -> p.setSendMode(null), "sendMode");
        assertInvalid(p -> p.setBatchSize(0), "batchSize");
        assertInvalid(p -> p.setFlushIntervalMillis(0), "flushInterval");
        assertInvalid(p -> p.setQueueCapacity(0), "queueCapacity");
        assertInvalid(p -> p.setMaxRetries(-1), "maxRetries");
        assertInvalid(p -> p.setInitialBackoffMillis(0), "initialBackoff");
        assertInvalid(
                p -> {
                    p.setInitialBackoffMillis(500);
                    p.setMaxBackoffMillis(100);
                },
                "maxBackoff");
        assertInvalid(p -> p.setSendTimeoutMillis(0), "sendTimeout");
        assertInvalid(p -> p.setMaxVariableValueLength(0), "maxVariableValueLength");
        assertInvalid(p -> p.setLookupCacheSize(0), "lookupCacheSize");
    }

    @Test
    void eventTypeFilterAcceptsCategoriesAndEventsAndRejectsTheRest() {
        HistoryOpenSearchOptions options =
                validate(p -> p.setEventTypes(List.of("process-instance", "task-instance.complete")));

        assertThat(options.accepts("process-instance", "end")).isTrue();
        assertThat(options.accepts("task-instance", "complete")).isTrue();
        assertThat(options.accepts("task-instance", "create")).isFalse();
        assertThat(options.accepts("incident", "create")).isFalse();
        assertThat(validate(p -> {}).accepts("incident", "create")).isTrue();
        assertInvalid(p -> p.setEventTypes(List.of("nonsense")), "unknown event type");
        assertInvalid(p -> p.setEventTypes(List.of("job-log")), "unknown event type");
        assertInvalid(p -> p.setEventTypes(List.of("process-instance.")), "unknown event type");
        assertInvalid(p -> p.setEventTypes(List.of(" ")), "must not be blank");
    }

    @Test
    void secretsNeverAppearInToString() {
        HistoryOpenSearchOptions options = validate(p -> {
            p.setUsername("indexer");
            p.setPassword("hunter2");
            p.setTrustStorePassword("trustpw");
            p.setKeyStorePassword("keypw");
        });
        HistoryOpenSearchOptions apiKey = validate(p -> p.setApiKey("QUJDREVGRw=="));

        assertThat(options.toString())
                .doesNotContain("hunter2", "trustpw", "keypw")
                .contains("indexer");
        assertThat(apiKey.toString()).doesNotContain("QUJDREVGRw==").contains("apiKey");
    }

    @Test
    void oneOptionsObjectPerPluginInstanceAndEngine() {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        plugin.setManageTemplates(false);
        plugin.setFailurePolicy(FailurePolicy.LOG_AND_COUNT);
        StandaloneInMemProcessEngineConfiguration first = config("first");
        plugin.preInit(first);
        StandaloneInMemProcessEngineConfiguration second = config("second");

        assertThatThrownBy(() -> plugin.preInit(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one plugin per engine");
        plugin.close();
    }

    @Test
    void refusesToCombineWithAnExplicitHistoryEventHandler() {
        StandaloneInMemProcessEngineConfiguration configuration = config("explicit");
        configuration.setHistoryEventHandler(new org.operaton.bpm.engine.impl.history.handler.DbHistoryEventHandler());

        assertThatThrownBy(() -> new HistoryOpenSearchPlugin().preInit(configuration))
                .isInstanceOf(HistoryOpenSearchException.class)
                .hasMessageContaining("already has a historyEventHandler");
    }

    @Test
    void handlerIsOnlyAvailableAfterTheEngineInitialisedThePlugin() {
        assertThatThrownBy(() -> new HistoryOpenSearchPlugin().getHandler())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been initialised");
    }

    private static StandaloneInMemProcessEngineConfiguration config(String name) {
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName(name);
        return configuration;
    }
}

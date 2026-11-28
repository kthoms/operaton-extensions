/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.historyopensearch.FailurePolicy;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchPlugin;
import org.operaton.extensions.plugin.historyopensearch.SendMode;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HistoryOpenSearchAutoConfigurationTest {

    private static final String P = "operaton.extensions.plugin.history-opensearch.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HistoryOpenSearchAutoConfiguration.class));

    @Test
    void registersThePluginWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(HistoryOpenSearchPlugin.class);
            var options = context.getBean(HistoryOpenSearchPlugin.class).validate();
            assertThat(options.connection().uris())
                    .extracting(Object::toString)
                    .containsExactly("http://localhost:9200");
            assertThat(options.indexPrefix()).isEqualTo("operaton");
            assertThat(options.sendMode()).isEqualTo(SendMode.ASYNC);
            assertThat(options.failurePolicy()).isEqualTo(FailurePolicy.FAIL);
            assertThat(options.includeVariableValues()).isFalse();
        });
    }

    @Test
    void mapsEveryProperty() {
        runner.withPropertyValues(
                        P + "uris=https://a:9200,https://b:9200",
                        P + "username=indexer",
                        P + "password=pw",
                        P + "tls.trust-store=/etc/ca.p12",
                        P + "tls.trust-store-password=tpw",
                        P + "tls.insecure-skip-verify=false",
                        P + "connect-timeout=2s",
                        P + "request-timeout=45s",
                        P + "index-prefix=acme",
                        P + "index-date-pattern=yyyy.MM.dd",
                        P + "number-of-shards=2",
                        P + "number-of-replicas=0",
                        P + "manage-templates=false",
                        P + "event-types=process-instance,task-instance.complete",
                        P + "failure-policy=log-and-count",
                        P + "send-mode=sync",
                        P + "batch-size=50",
                        P + "flush-interval=250ms",
                        P + "queue-capacity=77",
                        P + "max-retries=5",
                        P + "initial-backoff=100ms",
                        P + "max-backoff=2s",
                        P + "send-timeout=9s",
                        P + "keep-database-handler=false",
                        P + "include-variable-values=true",
                        P + "max-variable-value-length=100",
                        P + "lookup-cache-size=1000")
                .run(context -> {
                    var options = context.getBean(HistoryOpenSearchPlugin.class).validate();
                    assertThat(options.connection().uris())
                            .extracting(Object::toString)
                            .containsExactly("https://a:9200", "https://b:9200");
                    assertThat(options.connection().username()).isEqualTo("indexer");
                    assertThat(options.connection().tls().trustStore()).isEqualTo("/etc/ca.p12");
                    assertThat(options.connection().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(options.connection().requestTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(options.indexPrefix()).isEqualTo("acme");
                    assertThat(options.indexDatePattern()).isEqualTo("yyyy.MM.dd");
                    assertThat(options.numberOfShards()).isEqualTo(2);
                    assertThat(options.numberOfReplicas()).isZero();
                    assertThat(options.manageTemplates()).isFalse();
                    assertThat(options.eventTypes())
                            .containsExactlyInAnyOrder("process-instance", "task-instance.complete");
                    assertThat(options.failurePolicy()).isEqualTo(FailurePolicy.LOG_AND_COUNT);
                    assertThat(options.sendMode()).isEqualTo(SendMode.SYNC);
                    assertThat(options.batchSize()).isEqualTo(50);
                    assertThat(options.flushInterval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(options.queueCapacity()).isEqualTo(77);
                    assertThat(options.maxRetries()).isEqualTo(5);
                    assertThat(options.initialBackoff()).isEqualTo(Duration.ofMillis(100));
                    assertThat(options.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(options.sendTimeout()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(options.includeVariableValues()).isTrue();
                    assertThat(options.maxVariableValueLength()).isEqualTo(100);
                    assertThat(options.lookupCacheSize()).isEqualTo(1000);
                });
    }

    @Test
    void failsStartupOnInvalidConfiguration() {
        runner.withPropertyValues(P + "index-prefix=UPPER")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("indexPrefix"));
        runner.withPropertyValues(P + "event-types=nonsense")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(P + "username=u")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("password"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues(P + "enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HistoryOpenSearchPlugin.class));
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnPlugin() {
        runner.withBean(HistoryOpenSearchPlugin.class, HistoryOpenSearchPlugin::new)
                .run(context -> assertThat(context).hasSingleBean(HistoryOpenSearchPlugin.class));
    }
}

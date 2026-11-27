/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.extensions.connector.kafka.KafkaConnectorPlugin;
import org.operaton.extensions.connector.kafka.connector.KafkaProduceDelegate;
import org.operaton.extensions.connector.kafka.consumer.InboundAction;
import org.operaton.extensions.connector.kafka.consumer.PoisonHandling;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Auto-configuration and property binding; no broker is needed (clients connect lazily). */
class KafkaConnectorAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class EngineConfig {
        @Bean(destroyMethod = "close")
        ProcessEngine processEngine() {
            return new StandaloneInMemProcessEngineConfiguration()
                    .setJdbcUrl("jdbc:h2:mem:starter-test;DB_CLOSE_DELAY=1000")
                    .setJobExecutorActivate(false)
                    .setDatabaseSchemaUpdate("true")
                    .buildProcessEngine();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaConnectorAutoConfiguration.class))
            .withUserConfiguration(EngineConfig.class);

    @Test
    void nothingIsConfiguredWithoutBootstrapServers() {
        runner.run(context -> assertThat(context).doesNotHaveBean(KafkaConnectorPlugin.class));
    }

    @Test
    void producerBeansAreCreated() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.kafka.bootstrap-servers=localhost:1",
                        "operaton.extensions.connector.kafka.producer.properties.linger.ms=5")
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaConnectorPlugin.class);
                    assertThat(context).hasSingleBean(KafkaRecordSender.class);
                    assertThat(context.getBean("kafkaProduce")).isInstanceOf(KafkaProduceDelegate.class);
                    assertThat(context.getBean(KafkaConnectorPlugin.class).getConsumers())
                            .isEmpty();
                });
    }

    @Test
    void subscriptionsAreBoundValidatedAndStarted() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.kafka.bootstrap-servers=localhost:1",
                        "operaton.extensions.connector.kafka.subscriptions[0].name=orders",
                        "operaton.extensions.connector.kafka.subscriptions[0].topics=order-placed,order-updated",
                        "operaton.extensions.connector.kafka.subscriptions[0].action=start-process",
                        "operaton.extensions.connector.kafka.subscriptions[0].process-definition-key=order-process",
                        "operaton.extensions.connector.kafka.subscriptions[0].business-key=$.orderId",
                        "operaton.extensions.connector.kafka.subscriptions[0].variables.amount=$.amount",
                        "operaton.extensions.connector.kafka.subscriptions[0].on-poison=skip",
                        "operaton.extensions.connector.kafka.subscriptions[0].retry-backoff=250ms")
                .run(context -> {
                    var consumers = context.getBean(KafkaConnectorPlugin.class).getConsumers();
                    assertThat(consumers).hasSize(1);
                    var subscription = consumers.get(0).subscription();
                    assertThat(subscription.topics()).containsExactly("order-placed", "order-updated");
                    assertThat(subscription.action()).isEqualTo(InboundAction.START_PROCESS);
                    assertThat(subscription.onPoison()).isEqualTo(PoisonHandling.SKIP);
                    assertThat(subscription.retryBackoff()).isEqualTo(Duration.ofMillis(250));
                    assertThat(subscription.variables()).containsOnlyKeys("amount");
                    assertThat(consumers.get(0).isRunning()).isTrue();
                });
    }

    @Test
    void invalidSubscriptionPreventsStartup() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.kafka.bootstrap-servers=localhost:1",
                        "operaton.extensions.connector.kafka.subscriptions[0].name=broken",
                        "operaton.extensions.connector.kafka.subscriptions[0].topics=t",
                        "operaton.extensions.connector.kafka.subscriptions[0].action=correlate-message")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasStackTraceContaining("requires messageName"));
    }
}

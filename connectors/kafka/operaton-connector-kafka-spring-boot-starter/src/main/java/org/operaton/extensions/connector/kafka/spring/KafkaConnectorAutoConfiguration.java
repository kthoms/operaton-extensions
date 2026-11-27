/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.spring;

import java.time.Duration;
import java.util.List;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.extensions.connector.kafka.KafkaConnectorPlugin;
import org.operaton.extensions.connector.kafka.connector.KafkaProduceDelegate;
import org.operaton.extensions.connector.kafka.consumer.InboundSubscription;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Configures the Kafka connector when {@code operaton.extensions.connector.kafka.bootstrap-servers} is set: the
 * {@link KafkaConnectorPlugin} (a process engine plugin, picked up by the Operaton starter), the
 * {@code kafkaProduce} delegate bean, the {@link KafkaRecordSender} and a lifecycle that starts the configured
 * subscriptions' consumers after the context is complete.
 */
@AutoConfiguration(afterName = "org.operaton.bpm.spring.boot.starter.OperatonBpmAutoConfiguration")
@ConditionalOnClass(KafkaConnectorPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.connector.kafka", name = "bootstrap-servers")
@EnableConfigurationProperties(KafkaConnectorProperties.class)
public class KafkaConnectorAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    KafkaConnectorPlugin kafkaConnectorPlugin(KafkaConnectorProperties properties) {
        KafkaConnectorPlugin plugin = new KafkaConnectorPlugin();
        plugin.setBootstrapServers(properties.getBootstrapServers());
        plugin.setProducerProperties(properties.getProducer().getProperties());
        plugin.setSendTimeoutMillis(properties.getProducer().getSendTimeout().toMillis());
        plugin.setDeliveryFailureMode(properties.getProducer().getFailureMode());
        plugin.setConsumerProperties(properties.getConsumer().getProperties());
        Duration shutdownTimeout = properties.getConsumer().getShutdownTimeout();
        plugin.setShutdownTimeoutMillis(shutdownTimeout.toMillis());
        List<InboundSubscription> subscriptions = properties.getSubscriptions().stream()
                .map(KafkaConnectorProperties.Subscription::toSubscription)
                .toList();
        plugin.setSubscriptions(subscriptions);
        // Spring resolves ${kafkaProduce} from the context, and the lifecycle below starts the consumers
        plugin.setRegisterDelegateBean(false);
        plugin.setAutoStartConsumers(false);
        return plugin;
    }

    @Bean(name = KafkaConnectorPlugin.DELEGATE_BEAN)
    @ConditionalOnMissingBean(name = KafkaConnectorPlugin.DELEGATE_BEAN)
    KafkaProduceDelegate kafkaProduce(KafkaConnectorPlugin plugin) {
        return plugin.getProduceDelegate();
    }

    /** The plugin owns and closes the producer. */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    KafkaRecordSender kafkaRecordSender(KafkaConnectorPlugin plugin) {
        return plugin.getSender();
    }

    @Bean
    KafkaInboundLifecycle kafkaInboundLifecycle(KafkaConnectorPlugin plugin, ProcessEngine processEngine) {
        return new KafkaInboundLifecycle(plugin, processEngine);
    }
}

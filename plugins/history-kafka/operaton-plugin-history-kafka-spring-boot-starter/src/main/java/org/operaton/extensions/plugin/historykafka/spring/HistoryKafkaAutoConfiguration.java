/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.spring;

import org.operaton.extensions.plugin.historykafka.HistoryKafkaPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link HistoryKafkaPlugin} as a process engine plugin bean (the Operaton Spring
 * Boot starter picks up every {@code ProcessEnginePlugin} bean). Invalid configuration fails the
 * application context at startup. Mapping only; no logic.
 */
@AutoConfiguration
@ConditionalOnClass(HistoryKafkaPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.plugin.history-kafka", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(HistoryKafkaProperties.class)
public class HistoryKafkaAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public HistoryKafkaPlugin historyKafkaPlugin(HistoryKafkaProperties properties) {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers(properties.getBootstrapServers());
        plugin.setProducerProperties(properties.getProducer());
        plugin.setTopic(properties.getTopic());
        plugin.setTopicPrefix(properties.getTopicPrefix());
        plugin.setTopicMapping(properties.getTopicMapping());
        plugin.setEventTypes(properties.getEventTypes());
        plugin.setFormat(properties.getFormat());
        plugin.setCloudEventsSource(properties.getCloudEventsSource());
        plugin.setFailurePolicy(properties.getFailurePolicy());
        plugin.setSendMode(properties.getSendMode());
        plugin.setSendTimeoutMillis(properties.getSendTimeout().toMillis());
        plugin.setKeepDatabaseHandler(properties.isKeepDatabaseHandler());
        plugin.setIncludeVariableValues(properties.isIncludeVariableValues());
        plugin.validate();
        return plugin;
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.extensions.plugin.historykafka.internal.CloseOnEngineShutdownInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that publishes history events to Apache Kafka after the engine transaction
 * commits (see {@link KafkaHistoryEventHandler} for the delivery semantics).
 *
 * <p>Register it as a {@code processEnginePlugin} of the engine configuration. By default the
 * database history handler stays active next to Kafka; {@link #setKeepDatabaseHandler(boolean)
 * keepDatabaseHandler=false} replaces it (the history tables stay empty then).
 *
 * <p>One plugin instance serves one engine. The Kafka producer is created in {@code preInit}
 * (unless one was supplied with {@link #setProducer(Producer)}) and closed when the engine is
 * closed or {@link #close()} is called. Configuration errors fail engine startup with an
 * {@link IllegalArgumentException} or {@link HistoryKafkaException}.
 *
 * <p>Thread-safety: configure before the engine is built; after that the instance is safe for
 * concurrent use by the engine's threads.
 */
public class HistoryKafkaPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryKafkaPlugin.class);

    private static final String BOOTSTRAP_SERVERS = ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;

    private final HistoryKafkaOptions defaults = HistoryKafkaOptions.defaults();

    private String bootstrapServers;
    private Map<String, String> producerProperties = Map.of();
    private String topic;
    private String topicPrefix = defaults.topicPrefix();
    private Map<String, String> topicMapping = Map.of();
    private Collection<String> eventTypes = Set.of();
    private PayloadFormat format = defaults.format();
    private String cloudEventsSource;
    private FailurePolicy failurePolicy = defaults.failurePolicy();
    private SendMode sendMode = defaults.sendMode();
    private long sendTimeoutMillis = defaults.sendTimeout().toMillis();
    private boolean keepDatabaseHandler = true;
    private boolean includeVariableValues = defaults.includeVariableValues();
    private Producer<String, String> suppliedProducer;

    private volatile KafkaHistoryEventHandler handler;
    private volatile Producer<String, String> ownedProducer;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Kafka {@code bootstrap.servers}. Required unless {@code producerProperties} or a producer provides it. */
    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Extra Kafka producer properties, passed through (override the plugin's defaults, except the serializers). */
    public void setProducerProperties(Map<String, String> producerProperties) {
        this.producerProperties = producerProperties == null ? Map.of() : Map.copyOf(producerProperties);
    }

    /** One topic for all events. Without it, events go to {@code topicPrefix + category}. */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /** Prefix of the per-category topics; default {@code operaton.history.}. */
    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    /** Explicit topic per category ({@code process-instance}) or category and event ({@code process-instance.end}). */
    public void setTopicMapping(Map<String, String> topicMapping) {
        this.topicMapping = topicMapping == null ? Map.of() : Map.copyOf(topicMapping);
    }

    /**
     * Categories or category and event names to publish, e.g. {@code process-instance},
     * {@code activity-instance}, {@code task-instance.complete}. Empty: every history event.
     */
    public void setEventTypes(Collection<String> eventTypes) {
        this.eventTypes = eventTypes == null ? Set.of() : List.copyOf(eventTypes);
    }

    public void setFormat(PayloadFormat format) {
        this.format = format;
    }

    /** The CloudEvents {@code source}; default {@code urn:operaton:engine:<engine name>}. */
    public void setCloudEventsSource(String cloudEventsSource) {
        this.cloudEventsSource = cloudEventsSource;
    }

    public void setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public void setSendMode(SendMode sendMode) {
        this.sendMode = sendMode;
    }

    /** Timeout of {@link SendMode#SYNC} per transaction, and the producer's {@code max.block.ms} default. */
    public void setSendTimeoutMillis(long sendTimeoutMillis) {
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    /** Keep the engine's database history handler next to Kafka (default {@code true}). */
    public void setKeepDatabaseHandler(boolean keepDatabaseHandler) {
        this.keepDatabaseHandler = keepDatabaseHandler;
    }

    /** Include variable values in variable events (default {@code false}: they may carry personal data). */
    public void setIncludeVariableValues(boolean includeVariableValues) {
        this.includeVariableValues = includeVariableValues;
    }

    /**
     * Use this producer instead of creating one. The caller owns it: the plugin never closes it.
     * Serializers must be {@code String}.
     */
    public void setProducer(Producer<String, String> producer) {
        this.suppliedProducer = producer;
    }

    /** Validates the configuration; called by {@code preInit} and usable for fail-fast startup checks. */
    public HistoryKafkaOptions validate() {
        return validate("default");
    }

    private HistoryKafkaOptions validate(String engineName) {
        if (suppliedProducer == null
                && isBlank(bootstrapServers)
                && isBlank(producerProperties.get(BOOTSTRAP_SERVERS))) {
            throw new IllegalArgumentException(
                    "history-kafka: 'bootstrapServers' (or the producer property bootstrap.servers) is required");
        }
        for (String serializer :
                List.of(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)) {
            if (producerProperties.containsKey(serializer)) {
                throw new IllegalArgumentException("history-kafka: producer property '" + serializer
                        + "' must not be set, the plugin writes Strings");
            }
        }
        return new HistoryKafkaOptions(
                topic,
                topicPrefix,
                topicMapping,
                Set.copyOf(eventTypes),
                format,
                isBlank(cloudEventsSource) ? "urn:operaton:engine:" + engineName : cloudEventsSource,
                failurePolicy,
                sendMode,
                Duration.ofMillis(sendTimeoutMillis),
                includeVariableValues);
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (handler != null) {
            throw new IllegalStateException(
                    "history-kafka: this plugin instance already serves an engine; use one plugin per engine");
        }
        HistoryEventHandler existing = configuration.getHistoryEventHandler();
        if (existing != null) {
            throw new HistoryKafkaException(
                    "history-kafka: the engine configuration already has a historyEventHandler ("
                            + existing.getClass().getName()
                            + "); the plugin extends customHistoryEventHandlers, which the engine"
                            + " ignores in that case. Remove the explicit handler or add the Kafka handler to it yourself.");
        }
        HistoryKafkaOptions options = validate(configuration.getProcessEngineName());
        Producer<String, String> producer = suppliedProducer;
        if (producer == null) {
            producer = createProducer(configuration.getProcessEngineName());
            ownedProducer = producer;
        }
        handler = new KafkaHistoryEventHandler(producer, options);

        List<HistoryEventHandler> handlers = configuration.getCustomHistoryEventHandlers();
        List<HistoryEventHandler> updated = handlers == null ? new ArrayList<>() : new ArrayList<>(handlers);
        updated.add(handler);
        configuration.setCustomHistoryEventHandlers(updated);
        configuration.setEnableDefaultDbHistoryEventHandler(keepDatabaseHandler);

        List<CommandInterceptor> interceptors = configuration.getCustomPreCommandInterceptorsTxRequired();
        List<CommandInterceptor> withClose = interceptors == null ? new ArrayList<>() : new ArrayList<>(interceptors);
        withClose.add(new CloseOnEngineShutdownInterceptor(this::close));
        configuration.setCustomPreCommandInterceptorsTxRequired(withClose);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        if (configuration.getHistoryLevel() != null
                && configuration.getHistoryLevel().getId() == ProcessEngineConfigurationImpl.HISTORYLEVEL_NONE) {
            LOG.warn("history-kafka: the engine's history level is 'none', no history events will be published");
        }
    }

    private Producer<String, String> createProducer(String engineName) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "operaton-history-kafka-" + engineName);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(sendTimeoutMillis));
        if (!isBlank(bootstrapServers)) {
            config.put(BOOTSTRAP_SERVERS, bootstrapServers);
        }
        config.putAll(producerProperties);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try {
            return new KafkaProducer<>(new LinkedHashMap<>(config));
        } catch (RuntimeException e) {
            throw new HistoryKafkaException("history-kafka: cannot create the Kafka producer: " + e.getMessage(), e);
        }
    }

    /** The handler registered with the engine; available after {@code preInit}. */
    public KafkaHistoryEventHandler getHandler() {
        KafkaHistoryEventHandler current = handler;
        if (current == null) {
            throw new IllegalStateException("history-kafka: the plugin has not been initialised by an engine yet");
        }
        return current;
    }

    /**
     * Flushes and closes the producer the plugin created. Idempotent; called automatically when
     * the engine closes. A producer supplied with {@link #setProducer(Producer)} is left open.
     */
    @Override
    public void close() {
        Producer<String, String> producer = ownedProducer;
        if (producer != null && closed.compareAndSet(false, true)) {
            LOG.info("history-kafka: closing the Kafka producer");
            producer.close(Duration.ofMillis(sendTimeoutMillis));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

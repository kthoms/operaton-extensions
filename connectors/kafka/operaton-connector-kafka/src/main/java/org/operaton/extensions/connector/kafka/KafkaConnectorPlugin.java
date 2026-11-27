/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.connector.kafka.connector.KafkaProduceDelegate;
import org.operaton.extensions.connector.kafka.consumer.InboundSubscription;
import org.operaton.extensions.connector.kafka.consumer.KafkaProcessConsumer;
import org.operaton.extensions.connector.kafka.internal.ConnectorWiring;
import org.operaton.extensions.connector.kafka.producer.DeliveryFailureHandler;
import org.operaton.extensions.connector.kafka.producer.DeliveryFailureMode;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that configures the Kafka integration of one engine: the producer, the {@code kafka-produce}
 * connector, the {@link KafkaProduceDelegate} and the inbound subscriptions.
 *
 * <pre>{@code
 * KafkaConnectorPlugin kafka = new KafkaConnectorPlugin();
 * kafka.setBootstrapServers("localhost:9092");
 * kafka.setSubscriptions(List.of(InboundSubscription.builder("orders")
 *     .topics("order-placed").startProcess("order-process").businessKey("$.orderId").build()));
 * configuration.getProcessEnginePlugins().add(kafka);   // after ConnectProcessEnginePlugin
 * }</pre>
 *
 * <p>The delegate is registered as the expression bean {@value #DELEGATE_BEAN} on engines that resolve beans from
 * {@code ProcessEngineConfigurationImpl#getBeans()} (use {@code operaton:delegateExpression="${kafkaProduce}"});
 * Spring applications get the same bean from the starter. The Operaton Connect SPI keeps connectors in a JVM-wide
 * registry, so the {@code kafka-produce} connector uses the producer of the <em>last</em> plugin that was started in
 * the JVM; use one plugin per JVM or pass {@code bootstrapServers} per call.
 *
 * <p>Properties are plain setters for XML configuration. The plugin must be {@link #close() closed} after the engine
 * (the Spring Boot starter does so; a plain engine has no hook for it). Thread-safe after configuration.
 */
public class KafkaConnectorPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    /** Name of the delegate bean for {@code operaton:delegateExpression}. */
    public static final String DELEGATE_BEAN = "kafkaProduce";

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConnectorPlugin.class);

    private String bootstrapServers;
    private Map<String, String> producerProperties = Map.of();
    private Map<String, String> consumerProperties = Map.of();
    private long sendTimeoutMillis = KafkaRecordSender.DEFAULT_TIMEOUT.toMillis();
    private DeliveryFailureMode deliveryFailureMode = DeliveryFailureMode.LOG;
    private DeliveryFailureHandler deliveryFailureHandler;
    private List<InboundSubscription> subscriptions = List.of();
    private long shutdownTimeoutMillis = KafkaProcessConsumer.DEFAULT_SHUTDOWN_TIMEOUT.toMillis();
    private boolean autoStartConsumers = true;
    private boolean registerDelegateBean = true;

    private KafkaRecordSender sender;
    private KafkaProduceDelegate delegate;
    private final List<KafkaProcessConsumer> consumers = new ArrayList<>();

    /** {@code bootstrap.servers}; required. */
    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Further Kafka producer properties (serializers cannot be set). */
    public void setProducerProperties(Map<String, String> producerProperties) {
        this.producerProperties = Map.copyOf(producerProperties);
    }

    /** Further Kafka consumer properties, common to all subscriptions. */
    public void setConsumerProperties(Map<String, String> consumerProperties) {
        this.consumerProperties = Map.copyOf(consumerProperties);
    }

    /** How long an after-commit send waits for the acknowledgement, milliseconds; default 30000. */
    public void setSendTimeoutMillis(long sendTimeoutMillis) {
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    /** What happens when an after-commit send fails: {@code LOG} (default) or {@code THROW}. */
    public void setDeliveryFailureMode(DeliveryFailureMode deliveryFailureMode) {
        this.deliveryFailureMode = deliveryFailureMode;
    }

    /** A custom handler for failed after-commit sends; takes precedence over the mode. */
    public void setDeliveryFailureHandler(DeliveryFailureHandler deliveryFailureHandler) {
        this.deliveryFailureHandler = deliveryFailureHandler;
    }

    public void setSubscriptions(List<InboundSubscription> subscriptions) {
        this.subscriptions = List.copyOf(subscriptions);
    }

    /** How long stopping the consumers waits for the record in progress, milliseconds; default 15000. */
    public void setShutdownTimeoutMillis(long shutdownTimeoutMillis) {
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
    }

    /**
     * Whether the consumers start when the engine has been built (default). Set to {@code false} to start them
     * yourself with {@link #startConsumers(ProcessEngine)}, for example after the deployments.
     */
    public void setAutoStartConsumers(boolean autoStartConsumers) {
        this.autoStartConsumers = autoStartConsumers;
    }

    /**
     * Whether {@code preInit} exposes the delegate as the expression bean {@value #DELEGATE_BEAN} through
     * {@code ProcessEngineConfigurationImpl#getBeans()} (default). Set to {@code false} where expressions resolve
     * beans from a container (Spring): setting a beans map there would hide the container's beans.
     */
    public void setRegisterDelegateBean(boolean registerDelegateBean) {
        this.registerDelegateBean = registerDelegateBean;
    }

    /**
     * The producer; created on first use.
     *
     * @throws IllegalStateException when no bootstrap servers are configured
     * @throws org.apache.kafka.common.KafkaException for an invalid producer configuration
     */
    public synchronized KafkaRecordSender getSender() {
        if (sender == null) {
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalStateException("Kafka connector: bootstrapServers is required");
            }
            Map<String, Object> config = new HashMap<>(producerProperties);
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            DeliveryFailureHandler handler =
                    deliveryFailureHandler != null ? deliveryFailureHandler : deliveryFailureMode.handler();
            sender = new KafkaRecordSender(config, Duration.ofMillis(sendTimeoutMillis), handler);
        }
        return sender;
    }

    /** The delegate for {@code operaton:delegateExpression}; see {@link KafkaProduceDelegate}. */
    public synchronized KafkaProduceDelegate getProduceDelegate() {
        if (delegate == null) {
            delegate = new KafkaProduceDelegate(getSender());
        }
        return delegate;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        getSender();
        if (registerDelegateBean) {
            Map<Object, Object> existing = configuration.getBeans();
            if (existing != null && existing.getClass().getSimpleName().equals("SpringBeanFactoryProxyMap")) {
                LOG.warn(
                        "The engine resolves expression beans from a Spring context: declare a bean '{}' of type {} there",
                        DELEGATE_BEAN,
                        KafkaProduceDelegate.class.getSimpleName());
            } else {
                Map<Object, Object> beans = new HashMap<>();
                if (existing != null) {
                    beans.putAll(existing);
                }
                beans.put(DELEGATE_BEAN, getProduceDelegate());
                configuration.setBeans(beans);
            }
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        // after every preInit: the connect plugin (re)loads the connectors in its preInit
        if (ConnectorWiring.available()) {
            ConnectorWiring.wire(getSender());
        } else {
            LOG.info("Operaton Connect is not on the class path: the kafka-produce connector is not available");
        }
        if (autoStartConsumers) {
            startConsumers(processEngine);
        }
    }

    /**
     * Starts one {@link KafkaProcessConsumer} per subscription.
     *
     * @throws IllegalStateException when already started
     */
    public synchronized void startConsumers(ProcessEngine processEngine) {
        if (!consumers.isEmpty()) {
            throw new IllegalStateException("The Kafka consumers are already started");
        }
        Map<String, Object> config = new HashMap<>(consumerProperties);
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try {
            for (InboundSubscription subscription : subscriptions) {
                KafkaProcessConsumer consumer = KafkaProcessConsumer.create(
                        subscription,
                        config,
                        processEngine.getRuntimeService(),
                        getSender(),
                        Duration.ofMillis(shutdownTimeoutMillis));
                consumers.add(consumer);
                consumer.start();
            }
        } catch (RuntimeException e) {
            stopConsumers();
            throw e;
        }
    }

    /** Stops the consumers gracefully. */
    public synchronized void stopConsumers() {
        consumers.forEach(KafkaProcessConsumer::close);
        consumers.clear();
    }

    /** The running consumers, for status and tests. */
    public synchronized List<KafkaProcessConsumer> getConsumers() {
        return List.copyOf(consumers);
    }

    /** Stops the consumers and closes the producer. */
    @Override
    public synchronized void close() {
        stopConsumers();
        if (sender != null) {
            sender.close();
            sender = null;
            delegate = null;
        }
    }
}

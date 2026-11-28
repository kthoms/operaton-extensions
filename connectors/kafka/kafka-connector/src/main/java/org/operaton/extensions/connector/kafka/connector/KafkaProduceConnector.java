/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.connector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.context.BpmnExecutionContext;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.connect.ConnectorRequestException;
import org.operaton.connect.impl.AbstractConnector;
import org.operaton.connect.impl.AbstractRequestInvocation;
import org.operaton.connect.spi.ConnectorRequest;
import org.operaton.connect.spi.ConnectorRequestInterceptor;
import org.operaton.extensions.connector.kafka.internal.Values;
import org.operaton.extensions.connector.kafka.producer.DeliveryFailureHandler;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.operaton.extensions.connector.kafka.producer.ProduceAction;

/**
 * The {@code kafka-produce} connector. Registered through {@code META-INF/services} (see
 * {@link KafkaProduceConnectorProvider}); the engine's connect plugin creates one shared instance, so this class is
 * thread-safe.
 *
 * <p>The producer comes from, in this order: the request's {@code bootstrapServers} (and optional
 * {@code producerProperties}) parameters, which select a producer that is created on first use and cached; the
 * sender set with {@link #setSender(KafkaRecordSender)}, which the {@code KafkaConnectorPlugin} and the Spring Boot
 * starter do. Without either the request fails.
 */
public class KafkaProduceConnector extends AbstractConnector<KafkaProduceRequest, KafkaProduceResponse>
        implements AutoCloseable {

    /** The connector id used in BPMN: {@code <operaton:connectorId>kafka-produce</operaton:connectorId>}. */
    public static final String ID = "kafka-produce";

    public static final String BOOTSTRAP_SERVERS = "bootstrapServers";
    public static final String PRODUCER_PROPERTIES = "producerProperties";

    private static final Set<String> KNOWN_PARAMETERS = knownParameters();

    private volatile KafkaRecordSender sender;
    private final ConcurrentMap<String, KafkaRecordSender> adHocSenders = new ConcurrentHashMap<>();

    public KafkaProduceConnector() {
        super(ID);
    }

    private static Set<String> knownParameters() {
        Set<String> names = new HashSet<>(ProduceAction.PARAMETERS);
        names.add(BOOTSTRAP_SERVERS);
        names.add(PRODUCER_PROPERTIES);
        return Set.copyOf(names);
    }

    /** Sets the producer used when a request does not select its own. The connector does not close it. */
    public void setSender(KafkaRecordSender sender) {
        this.sender = sender;
    }

    @Override
    public KafkaProduceRequest createRequest() {
        return new KafkaProduceRequest(this);
    }

    @Override
    public KafkaProduceResponse execute(KafkaProduceRequest request) {
        Map<String, Object> parameters = new HashMap<>(request.getRequestParameters());
        for (String name : parameters.keySet()) {
            if (!KNOWN_PARAMETERS.contains(name)) {
                throw new ConnectorRequestException(
                        "Unknown parameter '%s' for connector '%s'; known: %s".formatted(name, ID, KNOWN_PARAMETERS));
            }
        }
        KafkaRecordSender producer = resolveSender(parameters);
        Invocation invocation = new Invocation(producer, parameters, request, requestInterceptors);
        try {
            return new KafkaProduceResponse((ProduceAction.Outcome) invocation.proceed());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorRequestException("Connector '%s' failed: %s".formatted(ID, e.getMessage()), e);
        }
    }

    private KafkaRecordSender resolveSender(Map<String, Object> parameters) {
        Object bootstrap = parameters.get(BOOTSTRAP_SERVERS);
        if (bootstrap != null) {
            Map<String, Object> properties = new TreeMap<>();
            Object extra = parameters.get(PRODUCER_PROPERTIES);
            if (extra != null) {
                if (!(extra instanceof Map<?, ?> map)) {
                    throw new ConnectorRequestException(
                            "Parameter '%s' of connector '%s' must be a map".formatted(PRODUCER_PROPERTIES, ID));
                }
                map.forEach((k, v) -> properties.put(String.valueOf(k), v));
            }
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Values.toText(bootstrap));
            return adHocSenders.computeIfAbsent(
                    properties.toString(),
                    key -> new KafkaRecordSender(
                            properties, KafkaRecordSender.DEFAULT_TIMEOUT, DeliveryFailureHandler.logging()));
        }
        KafkaRecordSender configured = sender;
        if (configured == null) {
            throw new ConnectorRequestException(
                    "No Kafka producer configured for connector '%s': register the KafkaConnectorPlugin (or the Spring"
                                    .formatted(ID)
                            + " Boot starter with operaton.extensions.connector.kafka.bootstrap-servers) or pass the '"
                            + BOOTSTRAP_SERVERS
                            + "' parameter");
        }
        return configured;
    }

    private static DelegateExecution currentExecution() {
        BpmnExecutionContext context = Context.getBpmnExecutionContext();
        return context == null ? null : context.getExecution();
    }

    /** Closes the producers created for requests that carried their own {@code bootstrapServers}. */
    @Override
    public void close() {
        List.copyOf(adHocSenders.values()).forEach(KafkaRecordSender::close);
        adHocSenders.clear();
    }

    private static final class Invocation extends AbstractRequestInvocation<Map<String, Object>> {
        private final KafkaRecordSender producer;

        Invocation(
                KafkaRecordSender producer,
                Map<String, Object> parameters,
                ConnectorRequest<?> request,
                List<ConnectorRequestInterceptor> interceptors) {
            super(parameters, request, interceptors);
            this.producer = producer;
        }

        @Override
        public Object invokeTarget() {
            return ProduceAction.execute(producer, target, currentExecution());
        }
    }
}

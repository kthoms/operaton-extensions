/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.connector;

import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;

/**
 * Request of {@code kafka-produce}: the parameters are listed in
 * {@link org.operaton.extensions.connector.kafka.producer.ProduceAction}; additionally {@code bootstrapServers} and
 * {@code producerProperties} select a producer for this call when no producer is configured on the engine.
 */
public class KafkaProduceRequest extends AbstractConnectorRequest<KafkaProduceResponse> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    KafkaProduceRequest(KafkaProduceConnector connector) {
        super((Connector) connector);
    }

    /** A request whose parameters can be set fluently; for use from Java. */
    public KafkaProduceRequest with(String name, Object value) {
        setRequestParameter(name, value);
        return this;
    }

    public KafkaProduceRequest topic(String topic) {
        return with("topic", topic);
    }

    public KafkaProduceRequest key(String key) {
        return with("key", key);
    }

    public KafkaProduceRequest value(Object value) {
        return with("value", value);
    }

    public KafkaProduceRequest headers(Map<String, ?> headers) {
        return with("headers", headers);
    }
}

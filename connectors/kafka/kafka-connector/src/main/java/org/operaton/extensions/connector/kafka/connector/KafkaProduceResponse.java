/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.connector;

import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorResponse;
import org.operaton.extensions.connector.kafka.producer.ProduceAction;

/**
 * Response of {@code kafka-produce}. Response parameters, usable in output parameter mappings:
 * {@code deliveryMode}, {@code topic}, and for {@code IMMEDIATE} sends {@code partition}, {@code offset} and
 * {@code timestamp}.
 */
public class KafkaProduceResponse extends AbstractConnectorResponse {

    private final ProduceAction.Outcome outcome;

    public KafkaProduceResponse(ProduceAction.Outcome outcome) {
        this.outcome = outcome;
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.put("deliveryMode", outcome.mode().name());
        responseParameters.put("topic", outcome.topic());
        if (outcome.result() != null) {
            responseParameters.put("partition", outcome.result().partition());
            responseParameters.put("offset", outcome.result().offset());
            responseParameters.put("timestamp", outcome.result().timestamp());
        }
    }
}

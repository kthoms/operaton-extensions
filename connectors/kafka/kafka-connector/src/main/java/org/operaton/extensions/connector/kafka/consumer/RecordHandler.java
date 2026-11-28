/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import java.util.HashMap;
import java.util.Map;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;

/**
 * Executes the subscription's action for one record. Stateless besides the immutable subscription; thread-safe.
 * Throws {@link MappingException} when the record does not fit the mapping and the engine's exception when the engine
 * call fails.
 */
final class RecordHandler {

    private final InboundSubscription subscription;
    private final RuntimeService runtimeService;

    RecordHandler(InboundSubscription subscription, RuntimeService runtimeService) {
        this.subscription = subscription;
        this.runtimeService = runtimeService;
    }

    void handle(InboundRecord record) {
        String businessKey = null;
        if (subscription.businessKey() != null) {
            Object value = subscription.businessKey().extract(record);
            businessKey = value == null ? null : value.toString();
        }
        Map<String, Object> variables = new HashMap<>();
        subscription.variables().forEach((name, source) -> {
            Object value = source.extract(record);
            if (value != null) {
                variables.put(name, value);
            }
        });
        if (subscription.action() == InboundAction.START_PROCESS) {
            start(businessKey, variables);
        } else {
            correlate(record, businessKey, variables);
        }
    }

    private void start(String businessKey, Map<String, Object> variables) {
        ProcessInstantiationBuilder builder =
                runtimeService.createProcessInstanceByKey(subscription.processDefinitionKey());
        if (subscription.tenantId() != null) {
            builder.processDefinitionTenantId(subscription.tenantId());
        }
        if (businessKey != null) {
            builder.businessKey(businessKey);
        }
        builder.setVariables(variables).execute();
    }

    private void correlate(InboundRecord record, String businessKey, Map<String, Object> variables) {
        MessageCorrelationBuilder builder = runtimeService.createMessageCorrelation(subscription.messageName());
        if (subscription.tenantId() != null) {
            builder.tenantId(subscription.tenantId());
        }
        if (businessKey != null) {
            builder.processInstanceBusinessKey(businessKey);
        }
        subscription.correlationKeys().forEach((name, source) -> {
            Object value = source.extract(record);
            if (value == null) {
                throw new MappingException("record %s: correlation key '%s' (source '%s') has no value"
                        .formatted(record.coordinates(), name, source));
            }
            builder.processInstanceVariableEquals(name, value);
        });
        builder.setVariables(variables).correlate();
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.connector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.operaton.extensions.connector.kafka.producer.ProduceAction;

/**
 * Service task delegate that sends a record to Kafka; the alternative to the {@code kafka-produce} connector with the
 * same parameters, given as <em>input parameters</em> of the service task (local variables of the activity), which
 * keeps the singleton delegate free of per-task state and thread-safe:
 *
 * <pre>{@code
 * <serviceTask id="publish" operaton:delegateExpression="${kafkaProduce}">
 *   <extensionElements><operaton:inputOutput>
 *     <operaton:inputParameter name="topic">order-confirmed</operaton:inputParameter>
 *     <operaton:inputParameter name="key">${execution.processBusinessKey}</operaton:inputParameter>
 *     <operaton:inputParameter name="value">${confirmation}</operaton:inputParameter>
 *   </operaton:inputOutput></extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * For {@code deliveryMode = IMMEDIATE} the activity gets the local variables {@code sentPartition},
 * {@code sentOffset} and {@code sentTimestamp}, which output parameters can map out. See {@link ProduceAction} for
 * the parameters. Thread-safe: immutable apart from the thread-safe sender.
 */
public class KafkaProduceDelegate implements JavaDelegate {

    private final KafkaRecordSender sender;

    public KafkaProduceDelegate(KafkaRecordSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> parameters = new HashMap<>();
        for (String name : ProduceAction.PARAMETERS) {
            if (execution.hasVariableLocal(name)) {
                parameters.put(name, execution.getVariableLocal(name));
            }
        }
        ProduceAction.Outcome outcome = ProduceAction.execute(sender, parameters, execution);
        if (outcome.result() != null) {
            execution.setVariableLocal("sentPartition", outcome.result().partition());
            execution.setVariableLocal("sentOffset", outcome.result().offset());
            execution.setVariableLocal("sentTimestamp", outcome.result().timestamp());
        }
    }
}

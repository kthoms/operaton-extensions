/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.example;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * The payment step of the order process: approves orders up to a limit. Stands in for a call to a payment provider.
 * Stateless, thread-safe.
 */
@Component("paymentService")
public class PaymentService implements JavaDelegate {

    static final BigDecimal LIMIT = new BigDecimal("1000");

    @Override
    public void execute(DelegateExecution execution) {
        BigDecimal amount = new BigDecimal(execution.getVariable("amount").toString());
        boolean approved = amount.compareTo(LIMIT) <= 0;
        execution.setVariable("paymentApproved", approved);
        if (approved) {
            execution.setVariable("transactionId", UUID.randomUUID().toString());
        }
    }

    /** The payload of the event published at the end of the process; the connector writes the map as JSON. */
    public Map<String, Object> event(DelegateExecution execution, String status) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("orderId", execution.getVariable("orderId"));
        event.put("status", status);
        event.put("amount", execution.getVariable("amount"));
        if (execution.getVariable("transactionId") != null) {
            event.put("transactionId", execution.getVariable("transactionId"));
        }
        return event;
    }
}

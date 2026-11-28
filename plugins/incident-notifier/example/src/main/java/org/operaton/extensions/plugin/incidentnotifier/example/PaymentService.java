/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a payment gateway client: payments above the limit are declined, which fails the
 * service task, exhausts its retries and creates an incident.
 */
@Component("paymentService")
public class PaymentService implements JavaDelegate {

    static final long LIMIT = 1000;

    @Override
    public void execute(DelegateExecution execution) {
        long amount = ((Number) execution.getVariable("amount")).longValue();
        if (amount > LIMIT) {
            throw new IllegalStateException("Payment gateway declined " + amount + " EUR for order "
                    + execution.getProcessBusinessKey() + ": amount exceeds the limit of " + LIMIT + " EUR");
        }
        execution.setVariable("paymentReference", "PAY-" + execution.getProcessBusinessKey());
    }
}

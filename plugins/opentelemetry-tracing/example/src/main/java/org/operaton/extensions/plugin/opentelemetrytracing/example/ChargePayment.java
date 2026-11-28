/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.example;

import java.util.UUID;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Charges the card; a declined card is a modelled business error. */
@Component("chargePayment")
public class ChargePayment implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        if ("declined".equals(execution.getVariable("card"))) {
            throw new BpmnError(
                    "PAYMENT_DECLINED", "The card of order " + execution.getProcessBusinessKey() + " was declined");
        }
        execution.setVariable("paymentId", UUID.randomUUID().toString());
    }
}

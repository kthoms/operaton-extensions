/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Classifies the risk of an application from its credit score (stand-in for a credit bureau call). */
@Component("creditCheck")
public class CreditCheck implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Object score = execution.getVariable("creditScore");
        if (!(score instanceof Number number)) {
            throw new IllegalArgumentException(
                    "Process instance " + execution.getProcessInstanceId() + " has no numeric 'creditScore' variable");
        }
        int value = number.intValue();
        String risk = value < 500 ? "HIGH" : value >= 700 ? "LOW" : "MEDIUM";
        execution.setVariable("risk", risk);
    }
}

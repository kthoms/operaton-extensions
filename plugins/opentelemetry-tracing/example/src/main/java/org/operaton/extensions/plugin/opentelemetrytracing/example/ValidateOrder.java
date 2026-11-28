/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Rejects orders without a positive total; the failure reaches the engine and the trace. */
@Component("validateOrder")
public class ValidateOrder implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Object total = execution.getVariable("orderTotal");
        if (!(total instanceof Number number) || number.longValue() <= 0) {
            throw new IllegalArgumentException(
                    "Order " + execution.getProcessBusinessKey() + " has no positive orderTotal: " + total);
        }
    }
}

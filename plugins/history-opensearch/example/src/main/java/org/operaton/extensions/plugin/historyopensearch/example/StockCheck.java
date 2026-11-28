/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Stand-in for a warehouse system: SKUs starting with {@code OUT-} are out of stock. */
@Component("stockCheck")
public class StockCheck implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Object sku = execution.getVariable("sku");
        if (!(sku instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Order " + execution.getProcessInstanceId() + " has no 'sku' variable; it cannot be checked");
        }
        execution.setVariable("inStock", !value.startsWith("OUT-"));
    }
}

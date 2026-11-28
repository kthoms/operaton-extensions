/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.VALID;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.VALIDATION_ERRORS;

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Checks the order and records the verdict. {@code ORDER.get(execution)} throws a {@code VariableNotFoundException}
 * naming the variable and the activity when the process was started without an order; the job then fails and the
 * engine raises an incident.
 */
@Component
public class ValidateOrderDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Order order = ORDER.get(execution);

        List<String> errors = new ArrayList<>();
        if (order.customer() == null || order.customer().isBlank()) {
            errors.add("customer is missing");
        }
        if (order.lines() == null || order.lines().isEmpty()) {
            errors.add("order has no lines");
        } else {
            for (Order.OrderLine line : order.lines()) {
                if (line.quantity() <= 0) {
                    errors.add("quantity of " + line.sku() + " must be positive");
                }
            }
        }

        VALIDATION_ERRORS.set(execution, errors);
        VALID.set(execution, errors.isEmpty());
    }
}

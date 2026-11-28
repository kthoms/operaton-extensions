/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.RECEIVED_AT;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.STATUS;

import java.util.Date;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;

/**
 * Moves the order to the status given by the {@code status} field of the listener in the BPMN. On
 * {@link OrderStatus#RECEIVED} it also stamps the time. The engine creates the listener from the class name, so it
 * holds no state besides the injected field expression.
 */
public class OrderStatusListener implements ExecutionListener {

    /** Injected by the engine from the {@code <operaton:field name="status">} of the BPMN. */
    private Expression status;

    @Override
    public void notify(DelegateExecution execution) {
        OrderStatus target = OrderStatus.valueOf((String) status.getValue(execution));
        STATUS.set(execution, target);
        if (target == OrderStatus.RECEIVED) {
            RECEIVED_AT.set(execution, new Date());
        }
    }
}

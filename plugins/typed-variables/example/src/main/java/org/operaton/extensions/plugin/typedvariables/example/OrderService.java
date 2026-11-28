/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.ORDER;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.extensions.plugin.typedvariables.TypedVariables;
import org.springframework.stereotype.Service;

/** Starts the order process for a submitted order. */
@Service
public class OrderService {

    /** The id of the BPMN process. */
    public static final String PROCESS_KEY = "order-processing";

    private final RuntimeService runtimeService;

    public OrderService(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * Starts an order process instance with the order number as business key.
     *
     * @param order the submitted order
     * @return the started instance
     */
    public ProcessInstance submit(Order order) {
        return runtimeService.startProcessInstanceByKey(
                PROCESS_KEY,
                order.id(),
                TypedVariables.builder().set(ORDER, order).build());
    }
}

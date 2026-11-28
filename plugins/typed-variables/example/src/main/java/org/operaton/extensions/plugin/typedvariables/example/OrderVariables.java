/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.operaton.extensions.plugin.typedvariables.TypedVariables.booleanVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.customVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.dateVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.enumVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.listVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.longVariable;

import java.util.Date;
import java.util.List;
import org.operaton.extensions.plugin.typedvariables.VariableFactory;
import org.operaton.extensions.plugin.typedvariables.VariableSet;

/**
 * Every variable of the {@code order-processing} process: its name and its type, declared once. The delegates, the
 * execution listener, the service that starts the process and the integration test all use these constants; the BPMN
 * refers to the same names in its conditions ({@code ${valid}}, {@code ${needsApproval}}, {@code ${approved}}).
 */
public final class OrderVariables {

    /** The submitted order, as JSON. Required at start. */
    public static final VariableFactory<Order> ORDER = customVariable("order", Order.class);

    /** When the order was received. */
    public static final VariableFactory<Date> RECEIVED_AT = dateVariable("receivedAt");

    /** The status of the order. */
    public static final VariableFactory<OrderStatus> STATUS = enumVariable("status", OrderStatus.class);

    /** Whether validation passed. */
    public static final VariableFactory<Boolean> VALID = booleanVariable("valid");

    /** Why validation failed; empty when it passed. */
    public static final VariableFactory<List<String>> VALIDATION_ERRORS =
            listVariable("validationErrors", String.class);

    /** The order total in cents. */
    public static final VariableFactory<Long> TOTAL_CENTS = longVariable("totalCents");

    /** Whether the total is above the approval threshold. */
    public static final VariableFactory<Boolean> NEEDS_APPROVAL = booleanVariable("needsApproval");

    /** The manager's decision; set when the user task completes, absent for small orders. */
    public static final VariableFactory<Boolean> APPROVED = booleanVariable("approved");

    /** All of the above, for checks such as "the process instance carries everything a finished order has". */
    public static final VariableSet ALL = VariableSet.of(OrderVariables.class);

    private OrderVariables() {}
}

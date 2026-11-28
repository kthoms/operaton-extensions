/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.operaton.extensions.plugin.typedvariables.TypedVariables.booleanVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.customVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.dateVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.doubleVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.enumVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.intVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.listVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.longVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.setVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.stringVariable;
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.uuidVariable;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;

/** The variable declarations of the tests: one class per process. */
final class TestVariables {

    enum Status {
        NEW,
        PRICED,
        PACKED
    }

    static final VariableFactory<String> ORDER_ID = stringVariable("orderId");
    static final VariableFactory<Integer> QUANTITY = intVariable("quantity");
    static final VariableFactory<Long> TOTAL_CENTS = longVariable("totalCents");
    static final VariableFactory<Double> DISCOUNT = doubleVariable("discount");
    static final VariableFactory<Boolean> PAID = booleanVariable("paid");
    static final VariableFactory<Date> CREATED = dateVariable("created");
    static final VariableFactory<UUID> TRACKING = uuidVariable("tracking");
    static final VariableFactory<Status> STATUS = enumVariable("status", Status.class);
    static final VariableFactory<TestOrder> ORDER = customVariable("order", TestOrder.class);
    static final VariableFactory<List<TestLine>> LINES = listVariable("lines", TestLine.class);
    static final VariableFactory<Set<String>> TAGS = setVariable("tags", String.class);
    static final VariableFactory<String> STEP = stringVariable("step");
    static final VariableFactory<Integer> PICKED = intVariable("picked");

    private TestVariables() {}
}

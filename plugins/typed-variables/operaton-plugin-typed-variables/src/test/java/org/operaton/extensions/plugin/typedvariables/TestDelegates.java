/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import static org.operaton.extensions.plugin.typedvariables.TestVariables.LINES;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.PICKED;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.STEP;
import static org.operaton.extensions.plugin.typedvariables.TestVariables.TOTAL_CENTS;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.extensions.plugin.typedvariables.TestOrder.TestLine;

/** The delegates and the listener of {@code typed-variables-test.bpmn}. */
final class TestDelegates {

    private TestDelegates() {}

    /** Reads the order and its lines, writes total and status. */
    public static class PriceDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            TestOrder order = ORDER.get(execution);
            long total = 0;
            for (TestLine line : LINES.get(execution)) {
                total += line.getQuantity() * 100L;
            }
            TOTAL_CENTS.set(execution, total);
            STATUS.set(execution, TestVariables.Status.PRICED);
            if (order.getId() == null) {
                throw new IllegalStateException("order without id");
            }
        }
    }

    /** Sets a variable that lives in the sub process scope only. */
    public static class StepListener implements ExecutionListener {
        @Override
        public void notify(DelegateExecution execution) {
            STEP.setLocal(execution, "packing");
        }
    }

    /** Reads the local variable of the sub process and writes the picked count locally and the status globally. */
    public static class PackDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            String step = STEP.getLocal(execution);
            PICKED.setLocal(execution, step.length());
            STATUS.set(execution, TestVariables.Status.PACKED);
        }
    }
}

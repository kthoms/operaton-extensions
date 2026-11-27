/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.example;

import java.math.BigDecimal;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/** Decides whether an invoice needs a manual approval: everything above 1000 does. */
public class CheckInvoiceDelegate implements JavaDelegate {

    static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("1000");

    @Override
    public void execute(DelegateExecution execution) {
        Object amount = execution.getVariable("amount");
        if (!(amount instanceof Number number)) {
            throw new IllegalArgumentException("process variable 'amount' of invoice "
                    + execution.getProcessBusinessKey() + " must be a number but is " + amount);
        }
        execution.setVariable("needsApproval", new BigDecimal(number.toString()).compareTo(APPROVAL_THRESHOLD) > 0);
    }
}

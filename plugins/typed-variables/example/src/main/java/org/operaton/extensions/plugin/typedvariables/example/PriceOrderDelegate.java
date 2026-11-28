/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.NEEDS_APPROVAL;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.TOTAL_CENTS;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Computes the total and decides whether a manager has to approve the order. */
@Component
public class PriceOrderDelegate implements JavaDelegate {

    private final long approvalThresholdCents;

    public PriceOrderDelegate(@Value("${example.approval-threshold-cents}") long approvalThresholdCents) {
        this.approvalThresholdCents = approvalThresholdCents;
    }

    @Override
    public void execute(DelegateExecution execution) {
        long total = ORDER.get(execution).lines().stream()
                .mapToLong(line -> line.quantity() * line.unitPriceCents())
                .sum();

        TOTAL_CENTS.set(execution, total);
        NEEDS_APPROVAL.set(execution, total > approvalThresholdCents);
    }
}

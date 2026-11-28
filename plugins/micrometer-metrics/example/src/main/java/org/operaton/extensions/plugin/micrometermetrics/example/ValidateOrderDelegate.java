/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Checks the ordered article against the catalog; an unknown article fails the job and opens an incident. */
@Component("validateOrderDelegate")
public class ValidateOrderDelegate implements JavaDelegate {

    static final String UNKNOWN_SKU = "UNKNOWN";

    @Override
    public void execute(DelegateExecution execution) {
        String sku = (String) execution.getVariable("sku");
        if (sku == null || UNKNOWN_SKU.equals(sku)) {
            throw new IllegalArgumentException(
                    "Article '" + sku + "' of order " + execution.getProcessBusinessKey() + " is not in the catalog");
        }
    }
}

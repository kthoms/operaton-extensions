/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.example;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** Stand-in for a carrier booking: hands out a tracking number. */
@Component("shipOrder")
public class ShipOrder implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        execution.setVariable("trackingNumber", "TRK-" + execution.getProcessInstanceId());
    }
}

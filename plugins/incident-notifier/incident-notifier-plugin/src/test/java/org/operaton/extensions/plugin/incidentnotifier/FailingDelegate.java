/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/** Service task implementation that always fails, like a payment gateway that is down. */
public class FailingDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        throw new IllegalStateException("payment gateway unavailable");
    }
}

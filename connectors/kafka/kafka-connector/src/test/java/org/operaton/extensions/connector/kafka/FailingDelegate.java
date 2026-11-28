/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/** Fails the transaction after the connector task ran. */
public class FailingDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        throw new IllegalStateException("boom");
    }
}

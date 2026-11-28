/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/** Always fails, to produce failed jobs, incidents and rolled-back transactions. */
public class FailingDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        throw new IllegalStateException("boom");
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.variable.value.ObjectValue;

/** Reads the deserialized applicant and does nothing else: the engine must not see a modification. */
public class ReadOnlyDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        ObjectValue value = execution.getVariableTyped("applicantProfile");
        Applicant applicant = (Applicant) value.getValue();
        execution.setVariableLocal("readLength", applicant.getName().length());
    }
}

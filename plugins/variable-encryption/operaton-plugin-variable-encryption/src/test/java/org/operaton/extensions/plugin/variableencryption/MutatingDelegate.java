/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.variable.value.ObjectValue;

/** Changes the deserialized applicant in place without setting the variable (implicit update). */
public class MutatingDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        ObjectValue value = execution.getVariableTyped("applicantProfile");
        ((Applicant) value.getValue()).setCity("Hamburg");
    }
}

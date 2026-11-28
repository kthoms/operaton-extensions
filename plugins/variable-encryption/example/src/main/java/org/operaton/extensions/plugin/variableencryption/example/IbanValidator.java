/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import java.math.BigInteger;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Checks the IBAN (ISO 13616 checksum). It reads the plain IBAN: decryption is transparent to
 * delegates. It stores only the outcome, {@code ibanValid}, which is not personal data.
 */
@Component("ibanValidator")
public class IbanValidator implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        Object iban = execution.getVariable("iban");
        if (!(iban instanceof String value)) {
            throw new IllegalArgumentException(
                    "Process instance " + execution.getProcessInstanceId() + " has no 'iban' variable");
        }
        execution.setVariable("ibanValid", isValid(value));
    }

    /**
     * Whether the IBAN has a valid checksum.
     *
     * @param iban the IBAN, spaces allowed
     * @return {@code true} if the mod 97 check passes
     */
    static boolean isValid(String iban) {
        String compact = iban.replace(" ", "").toUpperCase();
        if (!compact.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            return false;
        }
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).equals(BigInteger.ONE);
    }
}

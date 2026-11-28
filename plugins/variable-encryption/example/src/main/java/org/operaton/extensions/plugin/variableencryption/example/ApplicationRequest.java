/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import java.time.LocalDate;

/**
 * What an applicant submits.
 *
 * @param name full name
 * @param street street and number
 * @param city postal code and city
 * @param taxId tax identification number
 * @param iban the IBAN of the reference account
 * @param birthDate date of birth
 * @param passportNumber passport number, optional
 * @param product the requested product (not personal data)
 */
public record ApplicationRequest(
        String name,
        String street,
        String city,
        String taxId,
        String iban,
        LocalDate birthDate,
        String passportNumber,
        String product) {}

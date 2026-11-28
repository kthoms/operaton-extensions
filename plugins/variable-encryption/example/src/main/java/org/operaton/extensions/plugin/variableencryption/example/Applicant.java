/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

/** The applicant's profile, stored as the JSON object variable {@code applicantProfile}. */
public class Applicant {

    private String name;
    private String street;
    private String city;
    private String taxId;

    public Applicant() {}

    public Applicant(String name, String street, String city, String taxId) {
        this.name = name;
        this.street = street;
        this.city = city;
        this.taxId = taxId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }
}

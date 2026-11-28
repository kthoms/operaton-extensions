/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.io.Serializable;
import java.util.Objects;

/** A JSON and Java serializable test object. */
public class Applicant implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String city;
    private String taxId;

    public Applicant() {}

    public Applicant(String name, String city, String taxId) {
        this.name = name;
        this.city = city;
        this.taxId = taxId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    @Override
    public boolean equals(Object o) {
        return o instanceof Applicant a
                && Objects.equals(name, a.name)
                && Objects.equals(city, a.city)
                && Objects.equals(taxId, a.taxId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, city, taxId);
    }
}

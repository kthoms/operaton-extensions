/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A bean for the object, list and set tests; a plain POJO so Jackson and Java serialization both handle it. */
public class TestOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String customer;
    private List<TestLine> lines = new ArrayList<>();

    public TestOrder() {}

    public TestOrder(String id, String customer, List<TestLine> lines) {
        this.id = id;
        this.customer = customer;
        this.lines = new ArrayList<>(lines);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public List<TestLine> getLines() {
        return lines;
    }

    public void setLines(List<TestLine> lines) {
        this.lines = lines;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestOrder o
                && Objects.equals(id, o.id)
                && Objects.equals(customer, o.customer)
                && Objects.equals(lines, o.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, customer, lines);
    }

    /** An order line. */
    public static class TestLine implements Serializable {

        private static final long serialVersionUID = 1L;

        private String sku;
        private int quantity;

        public TestLine() {}

        public TestLine(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestLine l && quantity == l.quantity && Objects.equals(sku, l.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku, quantity);
        }
    }
}

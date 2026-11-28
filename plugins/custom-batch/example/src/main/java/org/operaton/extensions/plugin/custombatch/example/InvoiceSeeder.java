/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates invoices whose stored totals are wrong (zero), three lines each. The tax rate is 19 percent on
 * even and 7 percent on odd invoice ids, the lines follow a fixed pattern, so a test can compute the
 * expected totals independently.
 */
@Component
public class InvoiceSeeder {

    private final JdbcTemplate jdbc;

    public InvoiceSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes all invoices and creates {@code count} new ones with the ids 1 to {@code count}. */
    public void seed(int count) {
        jdbc.update("delete from invoice_line");
        jdbc.update("delete from invoice");
        List<Object[]> invoices = new ArrayList<>();
        List<Object[]> lines = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            invoices.add(new Object[] {id, "customer-" + (id % 40), taxRate(id), BigDecimal.ZERO});
            for (int line = 1; line <= 3; line++) {
                lines.add(new Object[] {id, quantity(id, line), unitPrice(id, line)});
            }
        }
        jdbc.batchUpdate("insert into invoice (id, customer, tax_rate, total) values (?, ?, ?, ?)", invoices);
        jdbc.batchUpdate("insert into invoice_line (invoice_id, quantity, unit_price) values (?, ?, ?)", lines);
    }

    /** The total an invoice must have after the recalculation, computed in Java. */
    public static BigDecimal expectedTotal(long id) {
        BigDecimal net = BigDecimal.ZERO;
        for (int line = 1; line <= 3; line++) {
            net = net.add(unitPrice(id, line).multiply(BigDecimal.valueOf(quantity(id, line))));
        }
        return net.multiply(BigDecimal.ONE.add(taxRate(id))).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal taxRate(long id) {
        return id % 2 == 0 ? new BigDecimal("0.1900") : new BigDecimal("0.0700");
    }

    private static int quantity(long id, int line) {
        return 1 + (int) ((id + line) % 5);
    }

    private static BigDecimal unitPrice(long id, int line) {
        return new BigDecimal("5.00").add(new BigDecimal("2.50").multiply(BigDecimal.valueOf((id * line) % 9)));
    }
}

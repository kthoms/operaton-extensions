/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.extensions.plugin.custombatch.CustomBatchJobHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Recomputes {@code invoice.total} from the invoice lines and the tax rate of the invoice. The items are
 * invoice ids; one batch execution job gets a chunk of them and updates the whole chunk with one
 * statement. The job runs in the engine's Spring transaction, so the update commits or rolls back with
 * the job.
 *
 * <p>A chunk that names an invoice that does not exist fails as a whole: the job is retried, and when
 * it runs out of retries it is reported by {@code CustomBatches.failedJobs} with all its invoice ids.
 */
@Component
public class RecalculateInvoiceTotalsHandler extends CustomBatchJobHandler<Long> {

    public static final String TYPE = "recalculate-invoice-totals";

    private static final String RECALCULATE = """
            update invoice i
               set total = round(coalesce((select sum(l.quantity * l.unit_price)
                                             from invoice_line l
                                            where l.invoice_id = i.id), 0) * (1 + i.tax_rate), 2),
                   recalculated_at = now()
             where i.id = any (?)
            """;

    private final JdbcTemplate jdbc;

    public RecalculateInvoiceTotalsHandler(JdbcTemplate jdbc) {
        super(TYPE, Long.class);
        this.jdbc = jdbc;
    }

    @Override
    protected void executeItems(List<Long> invoiceIds, CommandContext commandContext) {
        Long[] ids = invoiceIds.toArray(Long[]::new);
        int updated = jdbc.update(connection -> {
            var statement = connection.prepareStatement(RECALCULATE);
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            return statement;
        });
        if (updated != ids.length) {
            throw new IllegalStateException("Recalculated " + updated + " of " + ids.length
                    + " invoices; these do not exist: " + missing(invoiceIds));
        }
    }

    private List<Long> missing(List<Long> invoiceIds) {
        List<Long> existing = jdbc.queryForList(
                "select id from invoice where id = any (?)", Long.class, (Object) invoiceIds.toArray(Long[]::new));
        List<Long> missing = new ArrayList<>(invoiceIds);
        missing.removeAll(existing);
        return missing;
    }
}

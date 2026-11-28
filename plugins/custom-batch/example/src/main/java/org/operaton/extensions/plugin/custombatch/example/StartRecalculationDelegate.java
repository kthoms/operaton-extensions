/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import java.util.List;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.extensions.plugin.custombatch.CustomBatchBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The service task of the process: creates the batch for all invoices inside the transaction of the
 * process instance, and keeps the batch id in the process variable {@code batchId}.
 */
@Component("startRecalculationDelegate")
public class StartRecalculationDelegate implements JavaDelegate {

    /** Invoices per batch execution job. */
    public static final int INVOICES_PER_JOB = 50;

    private final RecalculateInvoiceTotalsHandler handler;
    private final JdbcTemplate jdbc;

    public StartRecalculationDelegate(RecalculateInvoiceTotalsHandler handler, JdbcTemplate jdbc) {
        this.handler = handler;
        this.jdbc = jdbc;
    }

    @Override
    public void execute(DelegateExecution execution) {
        List<Long> invoiceIds = jdbc.queryForList("select id from invoice order by id", Long.class);
        Batch batch = CustomBatchBuilder.of(handler, invoiceIds)
                .invocationsPerBatchJob(INVOICES_PER_JOB)
                .exclusive(false)
                .create();
        execution.setVariable("batchId", batch.getId());
    }
}

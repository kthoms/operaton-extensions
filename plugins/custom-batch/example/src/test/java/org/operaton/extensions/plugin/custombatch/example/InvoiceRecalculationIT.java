/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.extensions.plugin.custombatch.BatchProgress;
import org.operaton.extensions.plugin.custombatch.CustomBatchBuilder;
import org.operaton.extensions.plugin.custombatch.CustomBatches;
import org.operaton.extensions.plugin.custombatch.FailedBatchJob;
import org.operaton.extensions.plugin.custombatch.ItemCustomBatchJobHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Recalculates the totals of 1000 invoices in PostgreSQL through the process, a custom batch and the job
 * executor, and follows the batch through the engine's batch statistics. The error path names an invoice
 * that does not exist: its job fails, is reported with its invoice ids, and completes after the data fix.
 */
@SpringBootTest(properties = "example.auto-start=false")
@Testcontainers
class InvoiceRecalculationIT {

    static final int INVOICES = 1000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    InvoiceSeeder seeder;

    @Autowired
    InvoiceRecalculation recalculation;

    @Autowired
    CustomBatches batches;

    @Autowired
    RecalculateInvoiceTotalsHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FlagInvoiceHandler flagHandler;

    /**
     * Not part of the application: an item handler, to see that every item gets its own Spring transaction
     * on PostgreSQL. It marks an invoice by renaming its customer and fails for the invoices that are
     * "locked".
     */
    static class FlagInvoiceHandler extends ItemCustomBatchJobHandler<Long> {

        final JdbcTemplate jdbc;
        volatile Set<Long> locked = Set.of();

        FlagInvoiceHandler(JdbcTemplate jdbc) {
            super("flag-invoices", Long.class);
            this.jdbc = jdbc;
        }

        @Override
        protected void executeItem(Long invoiceId, CommandContext commandContext) {
            jdbc.update("update invoice set customer = 'flagged' where id = ?", invoiceId);
            if (locked.contains(invoiceId)) {
                throw new IllegalStateException("invoice " + invoiceId + " is locked");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlagConfiguration {
        @Bean
        FlagInvoiceHandler flagInvoiceHandler(JdbcTemplate jdbc) {
            return new FlagInvoiceHandler(jdbc);
        }
    }

    @BeforeEach
    void seedInvoicesWithWrongTotals() {
        seeder.seed(INVOICES);
        assertThat(jdbc.queryForObject("select count(*) from invoice where total = 0", Long.class))
                .isEqualTo(INVOICES);
    }

    private void assertEveryTotalIsRight() {
        Map<Long, BigDecimal> totals = new HashMap<>();
        jdbc.query("select id, total from invoice", rs -> {
            totals.put(rs.getLong("id"), rs.getBigDecimal("total"));
        });
        assertThat(totals).hasSize(INVOICES);
        List<Long> wrong = new ArrayList<>();
        totals.forEach((id, total) -> {
            if (InvoiceSeeder.expectedTotal(id).compareTo(total) != 0) {
                wrong.add(id);
            }
        });
        assertThat(wrong).as("invoices with a wrong total").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from invoice where recalculated_at is null", Long.class))
                .isZero();
    }

    @Test
    void recalculatesAThousandInvoicesInTwentyJobsAndReportsProgress() {
        String batchId = recalculation.start();

        BatchProgress first = recalculation.progress(batchId);
        assertThat(first.type()).isEqualTo(RecalculateInvoiceTotalsHandler.TYPE);
        assertThat(first.totalJobs()).isEqualTo(INVOICES / StartRecalculationDelegate.INVOICES_PER_JOB);

        List<Integer> completed = new ArrayList<>();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            BatchProgress progress = recalculation.progress(batchId);
            completed.add(progress.completedJobs());
            assertThat(progress.finished()).isTrue();
        });

        // the statistics never went backwards, and the batch ended with all jobs done and none failed
        assertThat(completed).isSorted();
        BatchProgress done = recalculation.progress(batchId);
        assertThat(done.totalJobs()).isEqualTo(20);
        assertThat(done.failedJobs()).isZero();
        assertEveryTotalIsRight();
    }

    @Test
    void aMissingInvoiceFailsItsJobAndTheDataFixLetsItComplete() {
        List<Long> ids = new ArrayList<>(jdbc.queryForList("select id from invoice order by id", Long.class));
        long missing = 5000;
        ids.add(missing);

        String batchId = batches.create(CustomBatchBuilder.of(handler, ids)
                        .invocationsPerBatchJob(50)
                        .exclusive(false))
                .getId();

        // 20 chunks of invoices run; the 21st chunk (the one with the missing id) runs out of retries
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            BatchProgress progress = recalculation.progress(batchId);
            assertThat(progress.completedJobs()).isEqualTo(20);
            assertThat(progress.failedJobs()).isEqualTo(1);
        });
        assertThat(recalculation.progress(batchId).finished()).isFalse();
        List<FailedBatchJob<Long>> failed = batches.failedJobs(handler, batchId);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).items()).containsExactly(missing);
        assertThat(failed.get(0).errorMessage()).contains("these do not exist: [5000]");

        // fix the data, give the failed job a new try
        jdbc.update("insert into invoice (id, customer, tax_rate, total) values (?, 'late', 0.1900, 0)", missing);
        assertThat(batches.retryFailedJobs(batchId, 1)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() ->
                        assertThat(recalculation.progress(batchId).finished()).isTrue());
        assertThat(jdbc.queryForObject("select count(*) from invoice where total = 0", Long.class))
                .isEqualTo(1L); // the late invoice has no lines: its total is 0 after the recalculation as well
        assertThat(jdbc.queryForObject("select count(*) from invoice where recalculated_at is null", Long.class))
                .isZero();
    }

    @Test
    void everyInvoiceIsItsOwnTransactionWhenTheHandlerWorksItemByItem() {
        flagHandler.locked = Set.of(13L, 57L);
        List<Long> ids = jdbc.queryForList("select id from invoice where id <= 100 order by id", Long.class);

        String batchId = batches.create(CustomBatchBuilder.of(flagHandler, ids).invocationsPerBatchJob(25))
                .getId();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            BatchProgress progress = batches.progress(batchId);
            assertThat(progress.completedJobs()).isEqualTo(2);
            assertThat(progress.failedJobs()).isEqualTo(2);
        });
        // 98 invoices keep their change; the two failing ones were rolled back, and the jobs hold only them
        assertThat(jdbc.queryForObject("select count(*) from invoice where customer = 'flagged'", Long.class))
                .isEqualTo(98L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from invoice where id in (13, 57) and customer = 'flagged'", Long.class))
                .isZero();
        List<FailedBatchJob<Long>> failed = batches.failedJobs(flagHandler, batchId);
        assertThat(failed).extracting(FailedBatchJob::items).containsExactlyInAnyOrder(List.of(13L), List.of(57L));
        assertThat(failed).allSatisfy(job -> assertThat(job.errorMessage()).contains("is locked"));

        flagHandler.locked = Set.of();
        assertThat(batches.retryFailedJobs(batchId, 1)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(
                        () -> assertThat(batches.progress(batchId).finished()).isTrue());
        assertThat(jdbc.queryForObject("select count(*) from invoice where customer = 'flagged'", Long.class))
                .isEqualTo(100L);
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import java.util.concurrent.atomic.AtomicReference;
import org.operaton.extensions.plugin.custombatch.BatchProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * On startup seeds the invoices and starts the recalculation; then logs the batch progress once a second
 * until the batch is finished. Switch off with {@code example.auto-start=false}.
 */
@Component
@ConditionalOnProperty(name = "example.auto-start", havingValue = "true", matchIfMissing = true)
public class RecalculationRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RecalculationRunner.class);

    private final InvoiceSeeder seeder;
    private final InvoiceRecalculation recalculation;
    private final int invoices;
    private final AtomicReference<String> runningBatch = new AtomicReference<>();

    public RecalculationRunner(
            InvoiceSeeder seeder, InvoiceRecalculation recalculation, @Value("${example.invoices:1000}") int invoices) {
        this.seeder = seeder;
        this.recalculation = recalculation;
        this.invoices = invoices;
    }

    @Override
    public void run(ApplicationArguments args) {
        seeder.seed(invoices);
        String batchId = recalculation.start();
        runningBatch.set(batchId);
        LOG.info("Started batch {} for {} invoices; watch it in Cockpit under Batches", batchId, invoices);
    }

    @Scheduled(fixedDelay = 1000)
    void logProgress() {
        String batchId = runningBatch.get();
        if (batchId == null) {
            return;
        }
        BatchProgress progress = recalculation.progress(batchId);
        LOG.info(
                "Batch {}: {} of {} jobs done, {} failed ({} %)",
                batchId,
                progress.completedJobs(),
                progress.totalJobs(),
                progress.failedJobs(),
                Math.round(progress.fractionCompleted() * 100));
        if (progress.finished()) {
            runningBatch.set(null);
        }
    }
}

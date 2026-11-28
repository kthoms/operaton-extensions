/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.Job;

/**
 * A batch lives in the database: it survives the engine that created it. The engines of this test share
 * one in-memory database and are closed and rebuilt in the middle of a batch.
 */
class RestartSafetyTest {

    private final String url = "jdbc:h2:mem:restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private final RecordingHandler handler = new RecordingHandler();

    private ProcessEngine startEngine(boolean withPlugin) {
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName("restart-" + UUID.randomUUID());
        configuration.setJdbcUrl(url);
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setJobExecutorActivate(false);
        configuration.setHistory("full");
        if (withPlugin) {
            configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(handler));
        }
        return configuration.buildProcessEngine();
    }

    @Test
    void aHalfProcessedBatchContinuesAfterARestartWithoutRepeatingOrLosingItems() {
        List<Long> items = LongStream.range(0, 40).boxed().toList();
        String batchId;
        List<Long> processedBeforeRestart;
        try (OpenEngine firstHandle = new OpenEngine(startEngine(true))) {
            ProcessEngine first = firstHandle.engine();
            Batch batch = CustomBatchBuilder.of(handler, items)
                    .invocationsPerBatchJob(5)
                    .jobsPerSeed(3)
                    .create(first);
            batchId = batch.getId();
            Jobs jobs = new Jobs(first.getManagementService());
            // one seed run creates 3 of the 8 jobs; 2 of them run; 25 items are still in the batch itself
            first.getManagementService().executeJob(jobs.seed(batch).get(0).getId());
            List<Job> execution = jobs.execution(batch);
            assertThat(execution).hasSize(3);
            execution.subList(0, 2).forEach(job -> first.getManagementService().executeJob(job.getId()));
            processedBeforeRestart = handler.processed();
            assertThat(processedBeforeRestart).hasSize(10);
        }

        try (OpenEngine secondHandle = new OpenEngine(startEngine(true))) {
            ProcessEngine second = secondHandle.engine();
            CustomBatches batches = new CustomBatches(second);
            BatchProgress restored = batches.progress(batchId);
            assertThat(restored.totalJobs()).isEqualTo(8);
            assertThat(restored.jobsCreated()).isEqualTo(3);
            assertThat(restored.completedJobs()).isEqualTo(2);

            Batch batch = second.getManagementService()
                    .createBatchQuery()
                    .batchId(batchId)
                    .singleResult();
            Jobs jobs = new Jobs(second.getManagementService());
            jobs.runSeedToEnd(batch);
            assertThat(jobs.execution(batch)).hasSize(6);
            jobs.runExecutionJobs(batch);
            jobs.runMonitor(batch);

            assertThat(batches.progress(batchId).finished()).isTrue();
        }

        // every item exactly once, across the restart
        assertThat(handler.processed())
                .containsExactlyInAnyOrderElementsOf(items)
                .doesNotHaveDuplicates();
    }

    @Test
    void anEngineWithoutTheHandlerFailsLoudlyAndLeavesTheBatchIntact() {
        String batchId;
        try (OpenEngine firstHandle = new OpenEngine(startEngine(true))) {
            ProcessEngine first = firstHandle.engine();
            batchId = CustomBatchBuilder.of(handler, List.of(1L, 2L, 3L))
                    .create(first)
                    .getId();
        }

        try (OpenEngine forgetfulHandle = new OpenEngine(startEngine(false))) {
            ProcessEngine forgetful = forgetfulHandle.engine();
            Batch batch = forgetful
                    .getManagementService()
                    .createBatchQuery()
                    .batchId(batchId)
                    .singleResult();
            Job seed = new Jobs(forgetful.getManagementService()).seed(batch).get(0);

            assertThatThrownBy(() -> forgetful.getManagementService().executeJob(seed.getId()))
                    .isInstanceOf(RuntimeException.class);

            assertThat(new CustomBatches(forgetful).progress(batchId).jobsCreated())
                    .isZero();
            assertThat(handler.processed()).isEmpty();
        }

        try (OpenEngine secondHandle = new OpenEngine(startEngine(true))) {
            ProcessEngine second = secondHandle.engine();
            Batch batch = second.getManagementService()
                    .createBatchQuery()
                    .batchId(batchId)
                    .singleResult();
            new Jobs(second.getManagementService()).runToEnd(batch);

            assertThat(new CustomBatches(second).progress(batchId).finished()).isTrue();
            assertThat(handler.processed()).containsExactlyInAnyOrder(1L, 2L, 3L);
        }
    }
}

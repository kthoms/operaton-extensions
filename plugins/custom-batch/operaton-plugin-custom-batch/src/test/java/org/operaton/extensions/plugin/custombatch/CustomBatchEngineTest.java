/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.batch.history.HistoricBatch;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** Runs custom batches on an embedded engine, stepping through every job by hand. */
class CustomBatchEngineTest {

    static final RecordingHandler HANDLER = new RecordingHandler();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                cfg.setJdbcUrl("jdbc:h2:mem:custom-batch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                cfg.setInvocationsPerBatchJobByBatchType(Map.of(RecordingHandler.TYPE, 4));
                cfg.getProcessEnginePlugins().add(new CustomBatchPlugin(HANDLER));
            })
            .build();

    final CustomBatches batches = new CustomBatches(engine.getProcessEngine());
    final Jobs jobs = new Jobs(engine.getManagementService());

    @AfterEach
    void cleanUp() {
        engine.getManagementService().createBatchQuery().list().forEach(batch -> batches.delete(batch.getId(), true));
        HANDLER.reset();
    }

    static List<Long> numbers(int count) {
        return LongStream.range(0, count).boxed().toList();
    }

    @Test
    void runsToCompletionInSeveralJobs() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(25))
                .invocationsPerBatchJob(5)
                .jobsPerSeed(2)
                .create(engine.getProcessEngine());

        assertThat(batch.getType()).isEqualTo(RecordingHandler.TYPE);
        assertThat(batch.getTotalJobs()).isEqualTo(5);
        assertThat(batches.progress(batch.getId()).jobsCreated()).isZero();

        // the seed job creates two jobs per run
        engine.getManagementService().executeJob(jobs.seed(batch).get(0).getId());
        assertThat(jobs.execution(batch)).hasSize(2);
        assertThat(batches.progress(batch.getId()).jobsCreated()).isEqualTo(2);
        jobs.runSeedToEnd(batch);
        assertThat(jobs.execution(batch)).hasSize(5);
        assertThat(jobs.monitor(batch)).hasSize(1);

        List<Job> execution = jobs.execution(batch);
        execution.subList(0, 2).forEach(job -> engine.getManagementService().executeJob(job.getId()));
        BatchProgress midway = batches.progress(batch.getId());
        assertThat(midway.totalJobs()).isEqualTo(5);
        assertThat(midway.completedJobs()).isEqualTo(2);
        assertThat(midway.remainingJobs()).isEqualTo(3);
        assertThat(midway.failedJobs()).isZero();
        assertThat(midway.finished()).isFalse();
        assertThat(midway.fractionCompleted()).isEqualTo(0.4);

        jobs.runExecutionJobs(batch);
        jobs.runMonitor(batch);

        assertThat(HANDLER.chunks)
                .hasSize(5)
                .allSatisfy(chunk -> assertThat(chunk).hasSize(5));
        assertThat(HANDLER.processed()).containsExactlyInAnyOrderElementsOf(numbers(25));
        BatchProgress done = batches.progress(batch.getId());
        assertThat(done.finished()).isTrue();
        assertThat(done.fractionCompleted()).isEqualTo(1.0);
        assertThat(engine.getManagementService().createBatchQuery().list()).isEmpty();
        assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
        // every job configuration is gone with its job
        assertThat(engine.getManagementService().getTableCount().get("ACT_GE_BYTEARRAY"))
                .isZero();

        HistoricBatch historic = engine.getHistoryService()
                .createHistoricBatchQuery()
                .batchId(batch.getId())
                .singleResult();
        assertThat(historic.getEndTime()).isNotNull();
        assertThat(historic.getExecutionStartTime()).isNotNull();
    }

    @Test
    void takesTheSplitFromTheEngineWhenTheBuilderDoesNotSetIt() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(10)).create(engine.getProcessEngine());

        // invocationsPerBatchJobByBatchType says 4 for this type: 4 + 4 + 2
        assertThat(batch.getInvocationsPerBatchJob()).isEqualTo(4);
        assertThat(batch.getTotalJobs()).isEqualTo(3);
        jobs.runToEnd(batch);
        assertThat(HANDLER.chunks).extracting(List::size).containsExactlyInAnyOrder(4, 4, 2);
    }

    @Test
    void anEmptyBatchFinishesImmediately() {
        Batch batch = CustomBatchBuilder.of(HANDLER, List.<Long>of()).create(engine.getProcessEngine());

        assertThat(batch.getTotalJobs()).isZero();
        jobs.runToEnd(batch);
        assertThat(batches.progress(batch.getId()).finished()).isTrue();
        assertThat(HANDLER.chunks).isEmpty();
    }

    @Test
    void appliesExclusiveTenantAndPriorityToTheJobs() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(6))
                .invocationsPerBatchJob(3)
                .exclusive(false)
                .tenantId("tenant-a")
                .jobPriority(42)
                .create(engine.getProcessEngine());

        assertThat(batch.getTenantId()).isEqualTo("tenant-a");
        jobs.runSeedToEnd(batch);
        assertThat(jobs.execution(batch)).hasSize(2).allSatisfy(job -> {
            assertThat(((JobEntity) job).isExclusive()).isFalse();
            assertThat(job.getTenantId()).isEqualTo("tenant-a");
            assertThat(job.getPriority()).isEqualTo(42);
        });
        jobs.runExecutionJobs(batch);
        jobs.runMonitor(batch);
    }

    @Test
    void createsTheBatchInsideARunningCommand() {
        Batch batch = engine.getProcessEngineConfiguration()
                .getCommandExecutorTxRequired()
                .execute(context -> CustomBatchBuilder.of(HANDLER, numbers(3)).create());

        assertThat(batches.progress(batch.getId()).totalJobs()).isEqualTo(1);
        jobs.runToEnd(batch);
        assertThat(HANDLER.processed()).containsExactlyInAnyOrder(0L, 1L, 2L);
    }

    @Test
    void theBatchRollsBackWithTheTransactionItWasCreatedIn() {
        assertThatThrownBy(() -> engine.getProcessEngineConfiguration()
                        .getCommandExecutorTxRequired()
                        .execute(context -> {
                            CustomBatchBuilder.of(HANDLER, numbers(3)).create();
                            throw new IllegalStateException("abort");
                        }))
                .hasMessage("abort");

        assertThat(engine.getManagementService().createBatchQuery().list()).isEmpty();
        assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    }

    @Test
    void refusesToCreateWithoutAnOpenCommandOrWithoutItems() {
        assertThatThrownBy(() -> CustomBatchBuilder.of(HANDLER, numbers(1)).create())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No engine command is running");
        assertThatThrownBy(() -> CustomBatchBuilder.of(HANDLER).create(engine.getProcessEngine()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("items");
    }

    @Test
    void validatesTheSettings() {
        CustomBatchBuilder<Long> builder = CustomBatchBuilder.of(HANDLER);
        assertThatThrownBy(() -> builder.invocationsPerBatchJob(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.jobsPerSeed(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.items(Arrays.asList(1L, null)).create(engine.getProcessEngine()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item 1");
    }

    @Test
    void refusesAHandlerThatIsNotRegisteredWithTheEngine() {
        AbstractCustomBatchJobHandler<Long> unknown = new CustomBatchJobHandler<>("never-registered", Long.class) {
            @Override
            protected void executeItems(List<Long> items, CommandContext commandContext) {}
        };

        assertThatThrownBy(() -> CustomBatchBuilder.of(unknown, numbers(1)).create(engine.getProcessEngine()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-registered")
                .hasMessageContaining("CustomBatchPlugin");
    }

    @Test
    void deletingARunningBatchRemovesJobsAndStoredConfigurations() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(20))
                .invocationsPerBatchJob(5)
                .create(engine.getProcessEngine());
        jobs.runSeedToEnd(batch);
        engine.getManagementService().executeJob(jobs.execution(batch).get(0).getId());
        assertThat(engine.getManagementService().getTableCount().get("ACT_GE_BYTEARRAY"))
                .isPositive();

        batches.delete(batch.getId(), false);

        assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
        assertThat(engine.getManagementService().createBatchQuery().list()).isEmpty();
        assertThat(engine.getManagementService().getTableCount().get("ACT_GE_BYTEARRAY"))
                .isZero();
        // the history stays unless it is deleted with it
        assertThat(batches.progress(batch.getId()).finished()).isTrue();
    }

    @Test
    void deletingWithHistoryForgetsTheBatch() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(4)).create(engine.getProcessEngine());

        batches.delete(batch.getId(), true);

        assertThatThrownBy(() -> batches.progress(batch.getId())).isInstanceOf(NotFoundException.class);
        assertThat(engine.getManagementService().getTableCount().get("ACT_GE_BYTEARRAY"))
                .isZero();
    }

    @Test
    void aSuspendedBatchWaitsUntilItIsActivated() {
        Batch batch = CustomBatchBuilder.of(HANDLER, numbers(4)).create(engine.getProcessEngine());

        batches.suspend(batch.getId());
        assertThat(batches.progress(batch.getId()).suspended()).isTrue();
        assertThat(engine.getManagementService()
                        .createJobQuery()
                        .jobDefinitionId(batch.getSeedJobDefinitionId())
                        .active()
                        .count())
                .isZero();

        batches.activate(batch.getId());
        assertThat(batches.progress(batch.getId()).suspended()).isFalse();
        jobs.runToEnd(batch);
        assertThat(HANDLER.processed()).hasSize(4);
    }

    @Test
    void progressOfAnUnknownBatchFailsLoudly() {
        assertThatThrownBy(() -> batches.progress("no-such-batch"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no-such-batch");
    }
}

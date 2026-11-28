/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** Failing items and failing chunks: retries, failed item reporting, the retry after the fix. */
class FailureHandlingTest {

    static final RecordingHandler CHUNKS = new RecordingHandler();
    static final PropertyItemHandler ITEMS = new PropertyItemHandler();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                cfg.setJdbcUrl("jdbc:h2:mem:failures-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                cfg.getProcessEnginePlugins().add(new CustomBatchPlugin(CHUNKS, ITEMS));
            })
            .build();

    final CustomBatches batches = new CustomBatches(engine.getProcessEngine());
    final Jobs jobs = new Jobs(engine.getManagementService());

    @AfterEach
    void cleanUp() {
        engine.getManagementService().createBatchQuery().list().forEach(batch -> batches.delete(batch.getId(), true));
        CHUNKS.reset();
        ITEMS.failOn = item -> false;
        ITEMS.reportedFailures.clear();
        engine.getProcessEngineConfiguration().getCommandExecutorTxRequired().execute(context -> {
            engine.getManagementService().getProperties().keySet().stream()
                    .filter(name -> name.startsWith(PropertyItemHandler.PREFIX))
                    .forEach(name -> context.getDbEntityManager()
                            .delete(context.getPropertyManager().findPropertyById(name)));
            return null;
        });
    }

    /** The items whose transaction committed, read from the properties the handler writes. */
    List<Long> rows() {
        return engine.getManagementService().getProperties().keySet().stream()
                .filter(name -> name.startsWith(PropertyItemHandler.PREFIX))
                .map(name -> Long.valueOf(name.substring(PropertyItemHandler.PREFIX.length())))
                .sorted()
                .toList();
    }

    private void executeUntilNoRetriesLeft(Job job) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Job current = engine.getManagementService()
                    .createJobQuery()
                    .jobId(job.getId())
                    .singleResult();
            if (current.getRetries() == 0) {
                return;
            }
            assertThatThrownBy(() -> engine.getManagementService().executeJob(job.getId()))
                    .isInstanceOf(RuntimeException.class);
        }
        throw new AssertionError("the job " + job.getId() + " never ran out of retries");
    }

    @Test
    void aFailingChunkIsRetriedAsAWholeThenRaisesAnIncidentAndKeepsAllItsItems() {
        CHUNKS.failOn = item -> item == 7;
        Batch batch = CustomBatchBuilder.of(CHUNKS, List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L))
                .invocationsPerBatchJob(5)
                .create(engine.getProcessEngine());
        jobs.runSeedToEnd(batch);

        List<RuntimeException> firstAttempt = jobs.tryRunExecutionJobs(batch);
        assertThat(firstAttempt).hasSize(1);
        assertThat(firstAttempt.get(0)).hasMessageContaining("cannot process item 7");
        Job bad = engine.getManagementService()
                .createJobQuery()
                .jobDefinitionId(batch.getBatchJobDefinitionId())
                .withException()
                .singleResult();
        executeUntilNoRetriesLeft(bad);

        BatchProgress progress = batches.progress(batch.getId());
        assertThat(progress.completedJobs()).isEqualTo(1);
        assertThat(progress.failedJobs()).isEqualTo(1);
        assertThat(progress.remainingJobs()).isEqualTo(1);
        List<Incident> incidents =
                engine.getRuntimeService().createIncidentQuery().list();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getIncidentMessage()).contains("cannot process item 7");

        List<FailedBatchJob<Long>> failed = batches.failedJobs(CHUNKS, batch.getId());
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).retries()).isZero();
        assertThat(failed.get(0).errorMessage()).contains("cannot process item 7");
        // the transaction of the chunk rolled back as a whole: all of its items are still to do
        assertThat(failed.get(0).items()).containsExactly(5L, 6L, 7L, 8L, 9L);
        // the batch stays open: its monitor does not finish it while a job is left
        jobs.runMonitor(batch);
        assertThat(batches.progress(batch.getId()).finished()).isFalse();

        CHUNKS.failOn = item -> false;
        assertThat(batches.retryFailedJobs(batch.getId(), 1)).isEqualTo(1);
        jobs.runExecutionJobs(batch);
        jobs.runMonitor(batch);

        assertThat(batches.progress(batch.getId()).finished()).isTrue();
        assertThat(CHUNKS.processed()).containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(engine.getRuntimeService().createIncidentQuery().count()).isZero();
    }

    @Test
    void failingItemsAreIsolatedReportedAndTheOtherItemsStayCommitted() {
        ITEMS.failOn = item -> item == 3 || item == 7;
        Batch batch = CustomBatchBuilder.of(ITEMS, List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L))
                .invocationsPerBatchJob(5)
                .create(engine.getProcessEngine());
        jobs.runSeedToEnd(batch);

        // first attempt: every job commits its good items and rolls back only the failing one
        List<RuntimeException> firstAttempt = jobs.tryRunExecutionJobs(batch);
        assertThat(firstAttempt)
                .hasSize(2)
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOf(CustomBatchItemsFailedException.class)
                        .hasMessageContaining("1 of 5 items"));
        assertThat(firstAttempt)
                .extracting(Throwable::getMessage)
                .anySatisfy(message -> assertThat(message).contains("Item 3: cannot process item 3"))
                .anySatisfy(message -> assertThat(message).contains("Item 7: cannot process item 7"));
        CustomBatchItemsFailedException failure = (CustomBatchItemsFailedException) firstAttempt.get(0);
        assertThat(failure.getFailedItems()).isEqualTo(1);
        assertThat(failure.getTotalItems()).isEqualTo(5);
        assertThat(failure.getCause()).hasMessageStartingWith("cannot process item");
        assertThat(rows()).containsExactly(0L, 1L, 2L, 4L, 5L, 6L, 8L, 9L);

        List<FailedBatchJob<Long>> afterFirstAttempt = batches.failedJobs(ITEMS, batch.getId());
        assertThat(afterFirstAttempt)
                .extracting(FailedBatchJob::items)
                .containsExactlyInAnyOrder(List.of(3L), List.of(7L));
        assertThat(afterFirstAttempt)
                .allSatisfy(job -> assertThat(job.retries()).isEqualTo(2));

        // the retries run only the failed items: the committed ones are not run again (primary key!)
        jobs.execution(batch).forEach(this::executeUntilNoRetriesLeft);

        assertThat(rows()).containsExactly(0L, 1L, 2L, 4L, 5L, 6L, 8L, 9L);
        assertThat(ITEMS.reportedFailures).contains(3L, 7L);
        assertThat(batches.progress(batch.getId()).failedJobs()).isEqualTo(2);
        List<FailedBatchJob<Long>> failed = batches.failedJobs(ITEMS, batch.getId());
        assertThat(failed).extracting(FailedBatchJob::items).containsExactlyInAnyOrder(List.of(3L), List.of(7L));
        assertThat(failed).allSatisfy(job -> assertThat(job.retries()).isZero());
        assertThat(engine.getRuntimeService().createIncidentQuery().count()).isEqualTo(2);

        // fix the cause, give the failed jobs new tries
        ITEMS.failOn = item -> false;
        assertThat(batches.retryFailedJobs(batch.getId(), 1)).isEqualTo(2);
        jobs.runExecutionJobs(batch);
        jobs.runMonitor(batch);

        assertThat(rows()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(batches.progress(batch.getId()).finished()).isTrue();
        // no job configuration is left: the byte arrays that remain are the stack traces of the failure logs
        assertThat(engine.getManagementService().getTableCount().get("ACT_GE_BYTEARRAY"))
                .isEqualTo(engine.getHistoryService()
                        .createHistoricJobLogQuery()
                        .failureLog()
                        .count());
    }

    @Test
    void theMessageOfAFailedJobListsAtMostTheFirstFailures() {
        ITEMS.failOn = item -> true;
        Batch batch = CustomBatchBuilder.of(ITEMS, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))
                .invocationsPerBatchJob(7)
                .create(engine.getProcessEngine());
        jobs.runSeedToEnd(batch);

        assertThatThrownBy(() -> jobs.runExecutionJobs(batch))
                .hasMessageContaining("7 of 7 items")
                .hasMessageContaining("Item 5: ")
                .hasMessageContaining("And 2 more")
                .hasMessageNotContaining("Item 6: ");
    }

    @Test
    void failedJobsOfAnotherTypeAreRejected() {
        Batch batch = CustomBatchBuilder.of(CHUNKS, List.of(1L)).create(engine.getProcessEngine());

        assertThatThrownBy(() -> batches.failedJobs(ITEMS, batch.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RecordingHandler.TYPE);
    }

    @Test
    void retryNeedsAtLeastOneTry() {
        assertThatThrownBy(() -> batches.retryFailedJobs("x", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

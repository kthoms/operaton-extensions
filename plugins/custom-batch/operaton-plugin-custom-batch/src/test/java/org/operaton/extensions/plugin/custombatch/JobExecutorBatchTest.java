/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** The real job executor drives a batch with parallel (non-exclusive) jobs to its end. */
class JobExecutorBatchTest {

    static final RecordingHandler HANDLER = new RecordingHandler();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                cfg.setJdbcUrl("jdbc:h2:mem:executor-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                cfg.setJobExecutorActivate(true);
                cfg.setBatchPollTime(1);
                cfg.getProcessEnginePlugins().add(new CustomBatchPlugin(HANDLER));
            })
            .build();

    @Test
    void theJobExecutorRunsAllJobsExactlyOnce() {
        CustomBatches batches = new CustomBatches(engine.getProcessEngine());
        List<Long> items = LongStream.range(0, 300).boxed().toList();

        Batch batch = CustomBatchBuilder.of(HANDLER, items)
                .invocationsPerBatchJob(10)
                .jobsPerSeed(7)
                .exclusive(false)
                .create(engine.getProcessEngine());

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() ->
                        assertThat(batches.progress(batch.getId()).finished()).isTrue());
        assertThat(HANDLER.processed()).hasSize(300).containsExactlyInAnyOrderElementsOf(items);
        assertThat(HANDLER.chunks).hasSize(30);
        // no job configuration is left (what remains are stack traces of the history job log)
        assertThat(engine.getManagementService().createJobQuery().list()).isEmpty();
    }
}

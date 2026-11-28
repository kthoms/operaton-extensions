/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.batch.BatchStatistics;
import org.operaton.bpm.engine.batch.history.HistoricBatch;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.runtime.Job;

/**
 * Create, follow and clean up custom batches of one engine, through the engine's public services.
 *
 * <p>The progress comes from the standard batch statistics, so Cockpit and the REST API show the same
 * numbers. Instances are immutable and thread-safe.
 */
public final class CustomBatches {

    private final ProcessEngine engine;

    /**
     * Creates the facade for an engine.
     *
     * @param engine the engine whose batches to manage
     */
    public CustomBatches(ProcessEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Creates the batch a builder describes, in this facade's engine.
     *
     * @param builder the builder
     * @return the batch
     */
    public Batch create(CustomBatchBuilder<?> builder) {
        return builder.create(engine);
    }

    /**
     * Creates a batch for the items with the engine's defaults for the split into jobs; use
     * {@link CustomBatchBuilder} and {@link #create(CustomBatchBuilder)} to set it.
     *
     * @param handler the handler
     * @param items the items
     * @param <T> the item type
     * @return the batch
     */
    public <T> Batch create(AbstractCustomBatchJobHandler<T> handler, List<? extends T> items) {
        return CustomBatchBuilder.of(handler, items).create(engine);
    }

    /**
     * Reads the progress of a batch. A batch that has finished is deleted from the runtime tables by
     * the engine, so its progress is then taken from the history (needs a history level above none).
     *
     * @param batchId the id of the batch
     * @return the progress
     * @throws NotFoundException if the engine knows no such batch, running or in its history
     */
    public BatchProgress progress(String batchId) {
        BatchStatistics statistics = engine.getManagementService()
                .createBatchStatisticsQuery()
                .batchId(batchId)
                .singleResult();
        if (statistics != null) {
            return new BatchProgress(
                    statistics.getId(),
                    statistics.getType(),
                    statistics.getTotalJobs(),
                    statistics.getJobsCreated(),
                    statistics.getCompletedJobs(),
                    statistics.getRemainingJobs(),
                    statistics.getFailedJobs(),
                    statistics.isSuspended(),
                    false);
        }
        HistoricBatch historic = engine.getHistoryService()
                .createHistoricBatchQuery()
                .batchId(batchId)
                .singleResult();
        if (historic != null && historic.getEndTime() != null) {
            return new BatchProgress(
                    historic.getId(),
                    historic.getType(),
                    historic.getTotalJobs(),
                    historic.getTotalJobs(),
                    historic.getTotalJobs(),
                    0,
                    0,
                    false,
                    true);
        }
        throw new NotFoundException("No batch with the id '" + batchId + "' is running or finished in the history");
    }

    /**
     * The jobs of a running batch that failed at least once, with the items they still have to process.
     *
     * @param handler the handler of the batch, which knows how to read the items
     * @param batchId the id of the batch
     * @param <T> the item type
     * @return the failed jobs, empty if none failed
     * @throws NotFoundException if no such batch is running
     * @throws IllegalArgumentException if the batch is not of the handler's type
     */
    public <T> List<FailedBatchJob<T>> failedJobs(AbstractCustomBatchJobHandler<T> handler, String batchId) {
        Batch batch = runningBatch(batchId);
        if (!handler.getType().equals(batch.getType())) {
            throw new IllegalArgumentException("The batch " + batchId + " has the type '" + batch.getType() + "', not '"
                    + handler.getType() + "'");
        }
        List<FailedBatchJob<T>> failed = new ArrayList<>();
        for (Job job : engine.getManagementService()
                .createJobQuery()
                .jobDefinitionId(batch.getBatchJobDefinitionId())
                .withException()
                .list()) {
            failed.add(new FailedBatchJob<>(
                    job.getId(), job.getRetries(), job.getExceptionMessage(), itemsOf(handler, job.getId())));
        }
        return failed;
    }

    /**
     * Gives the failed jobs of a running batch (those without retries left) new retries, so that the job
     * executor runs them again. An {@link ItemCustomBatchJobHandler} job then processes only its failed items.
     *
     * @param batchId the id of the batch
     * @param retries the number of tries to give each job, at least 1
     * @return the number of jobs reset
     * @throws NotFoundException if no such batch is running
     */
    public int retryFailedJobs(String batchId, int retries) {
        if (retries < 1) {
            throw new IllegalArgumentException("retries must be at least 1, but was " + retries);
        }
        Batch batch = runningBatch(batchId);
        ManagementService management = engine.getManagementService();
        List<String> jobIds =
                management
                        .createJobQuery()
                        .jobDefinitionId(batch.getBatchJobDefinitionId())
                        .noRetriesLeft()
                        .list()
                        .stream()
                        .map(Job::getId)
                        .toList();
        if (!jobIds.isEmpty()) {
            management.setJobRetries(jobIds, retries);
        }
        return jobIds.size();
    }

    /**
     * Suspends a batch: the job executor stops creating and running its jobs until it is activated.
     *
     * @param batchId the id of the batch
     */
    public void suspend(String batchId) {
        engine.getManagementService().suspendBatchById(batchId);
    }

    /**
     * Activates a suspended batch.
     *
     * @param batchId the id of the batch
     */
    public void activate(String batchId) {
        engine.getManagementService().activateBatchById(batchId);
    }

    /**
     * Deletes a running batch with its seed, monitor and batch execution jobs and their stored
     * configurations. Work already done stays done.
     *
     * @param batchId the id of the batch
     * @param cascadeToHistory whether to delete its history, its job logs and incidents as well
     */
    public void delete(String batchId, boolean cascadeToHistory) {
        engine.getManagementService().deleteBatch(batchId, cascadeToHistory);
    }

    private Batch runningBatch(String batchId) {
        Batch batch = engine.getManagementService()
                .createBatchQuery()
                .batchId(batchId)
                .singleResult();
        if (batch == null) {
            throw new NotFoundException("No running batch with the id '" + batchId + "'");
        }
        return batch;
    }

    private <T> List<T> itemsOf(AbstractCustomBatchJobHandler<T> handler, String jobId) {
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        return configuration.getCommandExecutorTxRequired().execute(context -> {
            JobEntity job = context.getJobManager().findJobById(jobId);
            if (job == null) {
                throw new NotFoundException("The job " + jobId + " does not exist any more");
            }
            ByteArrayEntity stored = context.getDbEntityManager()
                    .selectById(
                            ByteArrayEntity.class,
                            handler.newConfiguration(job.getJobHandlerConfigurationRaw())
                                    .getConfigurationByteArrayId());
            return handler.readConfiguration(stored.getBytes()).getItems();
        });
    }
}

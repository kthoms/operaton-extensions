/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * class org.camunda.community.batch.CustomBatchBuilder, adapted to org.operaton and reworked: typed
 * handler, validation, permission check, tenant, no shared mutable BatchEntity.
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.authorization.Permissions;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.batch.BatchEntity;
import org.operaton.bpm.engine.impl.batch.BatchJobHandler;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;
import org.operaton.bpm.engine.impl.persistence.entity.JobDefinitionEntity;
import org.operaton.bpm.engine.impl.util.ClockUtil;

/**
 * Creates a custom batch: the items, the handler that processes them, and how they are split into jobs.
 *
 * <pre>{@code
 * Batch batch = CustomBatchBuilder.of(handler)
 *         .items(invoiceIds)
 *         .invocationsPerBatchJob(50)   // 50 items per batch execution job
 *         .create(processEngine);
 * }</pre>
 *
 * <p>The batch is inserted together with its seed job; the job executor takes over from there. The
 * builder is a one-shot, not thread-safe value; use one per batch.
 *
 * @param <T> the item type
 */
public final class CustomBatchBuilder<T> {

    private final AbstractCustomBatchJobHandler<T> handler;
    private List<T> items;
    private int invocationsPerBatchJob;
    private int jobsPerSeed;
    private Long jobPriority;
    private boolean exclusive = true;
    private String tenantId;

    private CustomBatchBuilder(AbstractCustomBatchJobHandler<T> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Starts a batch for the handler. The handler must be registered with the engine that creates the
     * batch (see {@link CustomBatchPlugin}).
     *
     * @param handler the handler that processes the items
     * @param <T> the item type
     * @return a new builder
     */
    public static <T> CustomBatchBuilder<T> of(AbstractCustomBatchJobHandler<T> handler) {
        return new CustomBatchBuilder<>(handler);
    }

    /**
     * Starts a batch for the handler with its items.
     *
     * @param handler the handler that processes the items
     * @param items the items; must not contain {@code null}
     * @param <T> the item type
     * @return a new builder
     */
    public static <T> CustomBatchBuilder<T> of(
            AbstractCustomBatchJobHandler<T> handler, Collection<? extends T> items) {
        return new CustomBatchBuilder<>(handler).items(items);
    }

    /**
     * The items to process, in order. They are copied; an empty collection creates a batch that
     * finishes at once.
     *
     * @param items the items; must not contain {@code null}
     * @return this builder
     */
    public CustomBatchBuilder<T> items(Collection<? extends T> items) {
        Objects.requireNonNull(items, "items");
        this.items = new ArrayList<>(items);
        return this;
    }

    /**
     * How many items one batch execution job gets (the last job gets the rest). Without it the
     * engine's {@code invocationsPerBatchJobByBatchType} entry for the handler's type, then its
     * {@code invocationsPerBatchJob} setting (default 1) applies.
     *
     * @param invocationsPerBatchJob at least 1
     * @return this builder
     */
    public CustomBatchBuilder<T> invocationsPerBatchJob(int invocationsPerBatchJob) {
        if (invocationsPerBatchJob < 1) {
            throw new IllegalArgumentException(
                    "invocationsPerBatchJob must be at least 1, but was " + invocationsPerBatchJob);
        }
        this.invocationsPerBatchJob = invocationsPerBatchJob;
        return this;
    }

    /**
     * How many batch execution jobs one seed job invocation creates; the seed job runs again until all
     * jobs exist. Without it the engine's {@code batchJobsPerSeed} setting (default 100) applies.
     *
     * @param jobsPerSeed at least 1
     * @return this builder
     */
    public CustomBatchBuilder<T> jobsPerSeed(int jobsPerSeed) {
        if (jobsPerSeed < 1) {
            throw new IllegalArgumentException("jobsPerSeed must be at least 1, but was " + jobsPerSeed);
        }
        this.jobsPerSeed = jobsPerSeed;
        return this;
    }

    /**
     * The priority of the seed, monitor and batch execution jobs. It only matters when the job executor
     * acquires by priority. Without it the engine's {@code batchJobPriority} applies.
     *
     * @param jobPriority the priority
     * @return this builder
     */
    public CustomBatchBuilder<T> jobPriority(long jobPriority) {
        this.jobPriority = jobPriority;
        return this;
    }

    /**
     * Whether the batch execution jobs are exclusive, that is, never run in parallel for the same
     * batch. Default {@code true}: set {@code false} to let the job executor run the jobs of this batch
     * concurrently.
     *
     * @param exclusive the flag
     * @return this builder
     */
    public CustomBatchBuilder<T> exclusive(boolean exclusive) {
        this.exclusive = exclusive;
        return this;
    }

    /**
     * The tenant the batch belongs to; default none.
     *
     * @param tenantId the tenant id
     * @return this builder
     */
    public CustomBatchBuilder<T> tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * Creates the batch in its own transaction.
     *
     * @param processEngine the engine to create it in
     * @return the batch
     */
    public Batch create(ProcessEngine processEngine) {
        return create(processEngine.getProcessEngineConfiguration());
    }

    /**
     * Creates the batch in its own transaction, or in the running one when called from inside a command
     * (a delegate, a listener).
     *
     * @param configuration the configuration of the engine to create it in
     * @return the batch
     */
    public Batch create(ProcessEngineConfiguration configuration) {
        return create(((ProcessEngineConfigurationImpl) configuration).getCommandExecutorTxRequired());
    }

    /**
     * Creates the batch with the given executor.
     *
     * @param executor the command executor, normally {@code getCommandExecutorTxRequired()}
     * @return the batch
     */
    public Batch create(CommandExecutor executor) {
        requireItems();
        return executor.execute(this::createIn);
    }

    /**
     * Creates the batch in the transaction that is running, for use inside a delegate or a listener.
     *
     * @return the batch
     * @throws IllegalStateException if no engine command is running
     */
    public Batch create() {
        requireItems();
        CommandContext commandContext = null;
        IllegalStateException noCommand = null;
        try {
            // Operaton 2.0 and 2.1 return null without a command, 2.2 throws
            commandContext = Context.getCommandContext();
        } catch (IllegalStateException e) {
            noCommand = e;
        }
        if (commandContext == null) {
            throw new IllegalStateException(
                    "No engine command is running; pass the ProcessEngine to create(...) to create the batch in its own transaction",
                    noCommand);
        }
        return createIn(commandContext);
    }

    private void requireItems() {
        if (items == null) {
            throw new IllegalStateException("The items of the custom batch '" + handler.getType() + "' are not set");
        }
    }

    private Batch createIn(CommandContext commandContext) {
        ProcessEngineConfigurationImpl engine = commandContext.getProcessEngineConfiguration();
        BatchJobHandler<?> registered = engine.getBatchHandlers().get(handler.getType());
        if (!(registered instanceof AbstractCustomBatchJobHandler)) {
            throw new IllegalStateException("No custom batch handler is registered for the type '" + handler.getType()
                    + "' in the engine '" + engine.getProcessEngineName()
                    + "'; add it to the CustomBatchPlugin and register the plugin with the engine");
        }
        engine.getCommandCheckers().forEach(checker -> checker.checkCreateBatch(Permissions.CREATE));

        CustomBatchConfiguration<T> configuration = new CustomBatchConfiguration<>(items, exclusive, null);
        int perJob = invocationsPerBatchJob > 0
                ? invocationsPerBatchJob
                : handler.calculateInvocationsPerBatchJob(handler.getType(), configuration);

        BatchEntity batch = new BatchEntity();
        batch.setType(handler.getType());
        batch.setTenantId(tenantId);
        batch.setStartTime(ClockUtil.getCurrentTime());
        batch.setInvocationsPerBatchJob(perJob);
        batch.setBatchJobsPerSeed(jobsPerSeed > 0 ? jobsPerSeed : engine.getBatchJobsPerSeed());
        batch.setTotalJobs(Math.toIntExact((items.size() + (long) perJob - 1) / perJob));
        batch.setConfigurationBytes(handler.writeConfiguration(configuration));
        commandContext.getBatchManager().insertBatch(batch);

        JobDefinitionEntity seed = batch.createSeedJobDefinition(null);
        JobDefinitionEntity execution = batch.createBatchJobDefinition();
        JobDefinitionEntity monitor = batch.createMonitorJobDefinition();
        if (jobPriority != null) {
            seed.setJobPriority(jobPriority);
            execution.setJobPriority(jobPriority);
            monitor.setJobPriority(jobPriority);
        }
        batch.fireHistoricStartEvent();
        batch.createSeedJob();
        return batch;
    }
}

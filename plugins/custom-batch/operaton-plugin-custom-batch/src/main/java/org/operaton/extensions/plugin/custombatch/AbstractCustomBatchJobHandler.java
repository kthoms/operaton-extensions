/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * classes org.camunda.community.batch.core.CustomBatchCreateJobsHandler and
 * org.camunda.community.batch.CustomBatchJobHandler, adapted to org.operaton and reworked: JSON
 * configuration of typed items, execution start time of the batch, validated types.
 */
package org.operaton.extensions.plugin.custombatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.batch.BatchEntity;
import org.operaton.bpm.engine.impl.batch.BatchJobConfiguration;
import org.operaton.bpm.engine.impl.batch.BatchJobContext;
import org.operaton.bpm.engine.impl.batch.BatchJobDeclaration;
import org.operaton.bpm.engine.impl.batch.BatchJobHandler;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.DbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingListener;
import org.operaton.bpm.engine.impl.db.entitymanager.OptimisticLockingResult;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbEntityOperation;
import org.operaton.bpm.engine.impl.db.entitymanager.operation.DbOperation;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.JobDeclaration;
import org.operaton.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.JobManager;
import org.operaton.bpm.engine.impl.persistence.entity.MessageEntity;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.extensions.plugin.custombatch.internal.BatchTypes;
import org.operaton.extensions.plugin.custombatch.internal.ConfigurationCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class of every custom batch: the engine {@link BatchJobHandler} contract implemented once.
 *
 * <p>It stores the items of a batch as JSON (see {@link CustomBatchConfiguration}), lets the seed job
 * split them into batch execution jobs of {@code invocationsPerBatchJob} items, stores each job's slice
 * as JSON, hands a job's slice to {@link #executeJob} when the job runs, and deletes the stored slice
 * when the job is deleted. Extend {@link CustomBatchJobHandler} (one transaction per job) or
 * {@link ItemCustomBatchJobHandler} (one transaction per item) rather than this class.
 *
 * <p>Instances are registered with the engine through the {@link CustomBatchPlugin}; the engine
 * calls them concurrently from the job executor, so subclasses must be thread-safe (stateless, or
 * with thread-safe collaborators). The same type name must be registered on every node that runs the
 * job executor.
 *
 * @param <T> the item type: a type Jackson can write and read as JSON (a record, a POJO, a String or a number; see {@link #configureMapper})
 */
public abstract class AbstractCustomBatchJobHandler<T>
        implements BatchJobHandler<CustomBatchConfiguration<T>>, OptimisticLockingListener {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractCustomBatchJobHandler.class);

    private final String type;
    private final Type itemType;
    private final BatchJobDeclaration jobDeclaration;
    private volatile ConfigurationCodec<T> codec;

    /**
     * Creates a handler.
     *
     * @param type the batch type; also the job handler type, unique per engine and different from the
     *     engine's own types; at most 255 characters
     * @param itemType the type of one item: a class such as {@code InvoiceId.class}, or for a generic
     *     type a {@code ParameterizedType} such as {@code new TypeToken<Map<String, Long>>() {}.getType()}
     */
    protected AbstractCustomBatchJobHandler(String type, Type itemType) {
        this.type = BatchTypes.validate(type);
        this.itemType = Objects.requireNonNull(itemType, "itemType");
        this.jobDeclaration = new BatchJobDeclaration(type);
    }

    /** The batch type, also the job handler type of the batch execution jobs. */
    @Override
    public final String getType() {
        return type;
    }

    /** The type of one item, as given to the constructor. */
    public final Type getItemType() {
        return itemType;
    }

    /**
     * Runs one batch execution job.
     *
     * @param job the slice of items of this job and the batch settings; never empty unless the batch
     *     itself is
     * @param jobConfigurationId the id of the byte array that stores {@code job}; it is deleted when
     *     the job is deleted
     * @param commandContext the context of the job's transaction
     */
    protected abstract void executeJob(
            CustomBatchConfiguration<T> job, String jobConfigurationId, CommandContext commandContext);

    /**
     * Describes an item for failure messages and logs. The message of a failed job is stored in the
     * database and shown in Cockpit: override this when items are sensitive. The default is
     * {@code String.valueOf(item)} cut to 100 characters.
     *
     * @param item the item
     * @return a short description, never {@code null}
     */
    protected String describeItem(T item) {
        String text = String.valueOf(item);
        return text.length() <= 100 ? text : text.substring(0, 97) + "...";
    }

    /**
     * Hook to configure the Jackson mapper that writes and reads the items, for example to register the
     * {@code JavaTimeModule} for items with {@code java.time} types. Called once, lazily. The default
     * registers no module.
     *
     * @param builder the builder to configure
     * @return the builder
     */
    protected JsonMapper.Builder configureMapper(JsonMapper.Builder builder) {
        return builder;
    }

    // ---- BatchJobHandler: creating jobs ------------------------------------------------------

    @Override
    public boolean createJobs(BatchEntity batch) {
        JobManager jobManager = Context.getCommandContext().getJobManager();
        CustomBatchConfiguration<T> configuration = readConfiguration(batch.getConfigurationBytes());

        List<T> remaining = configuration.mutableItems();
        int invocationsPerBatchJob = batch.getInvocationsPerBatchJob();
        if (invocationsPerBatchJob < 1) {
            throw new ProcessEngineException("The custom batch " + batch.getId() + " of type '" + type
                    + "' has invocationsPerBatchJob " + invocationsPerBatchJob + "; it must be at least 1");
        }
        long wanted = (long) invocationsPerBatchJob * batch.getBatchJobsPerSeed();
        int toProcess = (int) Math.min(wanted, remaining.size());

        List<T> window = remaining.subList(0, toProcess);
        int createdJobs = 0;
        while (!window.isEmpty()) {
            List<T> slice = window.subList(0, Math.min(invocationsPerBatchJob, window.size()));

            CustomBatchConfiguration<T> jobConfiguration = configuration.forJob(new ArrayList<>(slice), batch.getId());
            ByteArrayEntity stored = new ByteArrayEntity();
            stored.setBytes(writeConfiguration(jobConfiguration));
            Context.getCommandContext().getByteArrayManager().insert(stored);

            JobEntity job = jobDeclaration.createJobInstance(new BatchJobContext(batch, stored));
            job.setExclusive(configuration.isExclusive());
            jobManager.insertAndHintJobExecutor(job);

            slice.clear();
            createdJobs++;
        }

        batch.setJobsCreated(batch.getJobsCreated() + createdJobs);
        batch.setConfigurationBytes(writeConfiguration(configuration));
        LOG.debug(
                "Batch {} of type '{}': created {} jobs, {} items left",
                batch.getId(),
                type,
                createdJobs,
                remaining.size());
        return remaining.isEmpty();
    }

    @Override
    public JobDeclaration<BatchJobContext, MessageEntity> getJobDeclaration() {
        return jobDeclaration;
    }

    @Override
    public void deleteJobs(BatchEntity batch) {
        for (JobEntity job : Context.getCommandContext()
                .getJobManager()
                .findJobsByJobDefinitionId(batch.getBatchJobDefinitionId())) {
            job.delete();
        }
    }

    // ---- JobHandler: executing and deleting a job --------------------------------------------

    @Override
    public final void execute(
            BatchJobConfiguration configuration,
            ExecutionEntity execution,
            CommandContext commandContext,
            String tenantId) {
        String byteArrayId = configuration.getConfigurationByteArrayId();
        ByteArrayEntity stored = commandContext.getDbEntityManager().selectById(ByteArrayEntity.class, byteArrayId);
        if (stored == null) {
            throw new ProcessEngineException("The configuration " + byteArrayId + " of a job of the custom batch type '"
                    + type + "' does not exist (the batch was deleted while the job ran?)");
        }
        CustomBatchConfiguration<T> job = readConfiguration(stored.getBytes());
        markExecutionStarted(commandContext, job.getBatchId());
        executeJob(job, byteArrayId, commandContext);
    }

    /** Sets the execution start time of the batch on its first job, as the engine's own batches do. */
    private void markExecutionStarted(CommandContext commandContext, String batchId) {
        if (batchId == null) {
            return;
        }
        BatchEntity batch = commandContext.getBatchManager().findBatchById(batchId);
        if (batch != null && batch.getExecutionStartTime() == null) {
            // several jobs may set it concurrently: the loser of the optimistic lock is ignored
            commandContext.getDbEntityManager().registerOptimisticLockingListener(this);
            batch.setExecutionStartTime(ClockUtil.now());
            batch.fireHistoricUpdateEvent();
        }
    }

    @Override
    public BatchJobConfiguration newConfiguration(String canonicalString) {
        return new BatchJobConfiguration(canonicalString);
    }

    @Override
    public void onDelete(BatchJobConfiguration configuration, JobEntity jobEntity) {
        String byteArrayId = configuration.getConfigurationByteArrayId();
        if (byteArrayId != null) {
            Context.getCommandContext().getByteArrayManager().deleteByteArrayById(byteArrayId);
        }
    }

    // ---- configuration (de)serialisation ------------------------------------------------------

    @Override
    public byte[] writeConfiguration(CustomBatchConfiguration<T> configuration) {
        return codec().write(configuration);
    }

    @Override
    public CustomBatchConfiguration<T> readConfiguration(byte[] serializedConfiguration) {
        return codec().read(serializedConfiguration);
    }

    @Override
    public int calculateInvocationsPerBatchJob(String batchType, CustomBatchConfiguration<T> configuration) {
        ProcessEngineConfigurationImpl engineConfiguration = Context.getProcessEngineConfiguration();
        Map<String, Integer> byType = engineConfiguration.getInvocationsPerBatchJobByBatchType();
        Integer invocations = byType == null ? null : byType.get(batchType);
        return invocations != null ? invocations : engineConfiguration.getInvocationsPerBatchJob();
    }

    private ConfigurationCodec<T> codec() {
        ConfigurationCodec<T> result = codec;
        if (result == null) {
            // the codec is immutable: two threads racing here build equal instances
            ObjectMapper mapper = configureMapper(JsonMapper.builder()).build();
            result = new ConfigurationCodec<>(itemType, mapper);
            codec = result;
        }
        return result;
    }

    // ---- OptimisticLockingListener ------------------------------------------------------------

    @Override
    public Class<? extends DbEntity> getEntityType() {
        return BatchEntity.class;
    }

    @Override
    public OptimisticLockingResult failedOperation(DbOperation operation) {
        if (operation instanceof DbEntityOperation) {
            return OptimisticLockingResult.IGNORE;
        }
        return OptimisticLockingResult.THROW;
    }
}

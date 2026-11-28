/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.extensions.plugin.custombatch.BatchProgress;
import org.operaton.extensions.plugin.custombatch.CustomBatches;
import org.springframework.stereotype.Service;

/** Starts the recalculation process and reports how far its batch is. */
@Service
public class InvoiceRecalculation {

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final CustomBatches batches;

    public InvoiceRecalculation(RuntimeService runtimeService, HistoryService historyService, CustomBatches batches) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.batches = batches;
    }

    /**
     * Starts the process {@code invoice-recalculation}; its service task creates the batch.
     *
     * @return the id of the batch
     */
    public String start() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("invoice-recalculation");
        return (String) historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("batchId")
                .singleResult()
                .getValue();
    }

    /** Where the batch stands, from the engine's batch statistics. */
    public BatchProgress progress(String batchId) {
        return batches.progress(batchId);
    }
}

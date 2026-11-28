/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;

/**
 * The elements of one executable process definition that count towards coverage, and its BPMN XML for
 * the report. Immutable.
 *
 * <p>Ported from the {@code ExecutionContextModelProvider} of camunda-process-test-coverage.
 */
public final class ProcessModel {

    private final String key;
    private final String definitionId;
    private final int version;
    private final String versionTag;
    private final String bpmnXml;
    private final Set<String> flowNodeIds;
    private final Set<String> sequenceFlowIds;

    public ProcessModel(
            String key,
            String definitionId,
            int version,
            String versionTag,
            String bpmnXml,
            Collection<String> flowNodeIds,
            Collection<String> sequenceFlowIds) {
        this.key = Objects.requireNonNull(key, "key");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
        this.version = version;
        this.versionTag = versionTag;
        this.bpmnXml = Objects.requireNonNull(bpmnXml, "bpmnXml");
        this.flowNodeIds = Set.copyOf(flowNodeIds);
        this.sequenceFlowIds = Set.copyOf(sequenceFlowIds);
    }

    /**
     * Reads the deployed definition {@code definitionId} and collects its executable flow nodes and the
     * sequence flows that start at them.
     */
    public static ProcessModel load(RepositoryService repositoryService, String definitionId) {
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        BpmnModelInstance instance = repositoryService.getBpmnModelInstance(definitionId);
        String key = definition.getKey();

        Set<FlowNode> nodes = new LinkedHashSet<>();
        for (FlowNode node : instance.getModelElementsByType(FlowNode.class)) {
            if (isInExecutableProcess(node, key)) {
                nodes.add(node);
            }
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        nodes.forEach(node -> nodeIds.add(node.getId()));
        Set<String> flowIds = new LinkedHashSet<>();
        for (SequenceFlow flow : instance.getModelElementsByType(SequenceFlow.class)) {
            if (nodes.contains(flow.getSource())) {
                flowIds.add(flow.getId());
            }
        }
        return new ProcessModel(
                key,
                definitionId,
                definition.getVersion(),
                definition.getVersionTag(),
                Bpmn.convertToString(instance),
                nodeIds,
                flowIds);
    }

    private static boolean isInExecutableProcess(ModelElementInstance element, String processKey) {
        ModelElementInstance current = element;
        while (current != null) {
            if (current instanceof Process process) {
                return process.isExecutable() && processKey.equals(process.getId());
            }
            current = current.getParentElement();
        }
        return false;
    }

    public String key() {
        return key;
    }

    public String definitionId() {
        return definitionId;
    }

    public int version() {
        return version;
    }

    /** The version tag of the definition, or {@code null} when it has none. */
    public String versionTag() {
        return versionTag;
    }

    public String bpmnXml() {
        return bpmnXml;
    }

    public Set<String> flowNodeIds() {
        return flowNodeIds;
    }

    public Set<String> sequenceFlowIds() {
        return sequenceFlowIds;
    }

    /** Flow nodes plus sequence flows. */
    public int totalElementCount() {
        return flowNodeIds.size() + sequenceFlowIds.size();
    }
}

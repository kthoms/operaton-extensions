package org.operaton.extensions.plugin.typedvariables.test;

import java.util.List;
import java.util.Objects;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.typedvariables.VariableSource;

/**
 * Reads the variables of a finished (or running) process instance from the history. Variables of other scopes than the
 * process instance, such as sub process locals, are not visible. Needs history level {@code audit} or {@code full}.
 */
final class HistoricVariableSource implements VariableSource {

    private final HistoryService historyService;
    private final String processInstanceId;

    HistoricVariableSource(HistoryService historyService, String processInstanceId) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.processInstanceId = Objects.requireNonNull(processInstanceId, "processInstanceId");
    }

    @Override
    public TypedValue getTyped(String name) {
        List<HistoricVariableInstance> instances = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceIdIn(processInstanceId)
                .variableName(name)
                .list();
        for (HistoricVariableInstance instance : instances) {
            if (processInstanceId.equals(instance.getExecutionId())) {
                return instance.getTypedValue();
            }
        }
        return null;
    }

    @Override
    public TypedValue getLocalTyped(String name) {
        // the process instance scope is the only scope of a historic process instance
        return getTyped(name);
    }

    @Override
    public String describe() {
        return "historic process instance " + processInstanceId;
    }
}

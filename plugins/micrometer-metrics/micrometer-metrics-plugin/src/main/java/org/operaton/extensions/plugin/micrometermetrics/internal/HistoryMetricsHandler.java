/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Locale;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.extensions.plugin.micrometermetrics.MetricNames;

/**
 * A {@link HistoryEventHandler} that only counts: process instance start/end and incident
 * create/resolve/delete. It never stores anything and is meant to run next to the engine's own
 * handler. Counters are incremented after the transaction that produced the event committed, so a
 * rollback does not count.
 *
 * <p>Thread-safe: all state is in the (thread-safe) Micrometer meters and the guards.
 */
public final class HistoryMetricsHandler implements HistoryEventHandler {

    private static final String START = HistoryEventTypes.PROCESS_INSTANCE_START.getEventName();
    private static final String END = HistoryEventTypes.PROCESS_INSTANCE_END.getEventName();
    private static final String INCIDENT_CREATE = HistoryEventTypes.INCIDENT_CREATE.getEventName();
    private static final String INCIDENT_RESOLVE = HistoryEventTypes.INCIDENT_RESOLVE.getEventName();
    private static final String INCIDENT_DELETE = HistoryEventTypes.INCIDENT_DELETE.getEventName();

    private final EngineMeters meters;
    private final CardinalityGuard definitionKeys;
    private final CardinalityGuard incidentTypes;

    public HistoryMetricsHandler(EngineMeters meters, CardinalityGuard definitionKeys, CardinalityGuard incidentTypes) {
        this.meters = meters;
        this.definitionKeys = definitionKeys;
        this.incidentTypes = incidentTypes;
    }

    @Override
    public void handleEvent(HistoryEvent event) {
        Counter counter = counterFor(event);
        if (counter == null) {
            return;
        }
        CommandContext commandContext = Context.getCommandContext();
        if (commandContext == null) {
            counter.increment();
        } else {
            commandContext
                    .getTransactionContext()
                    .addTransactionListener(TransactionState.COMMITTED, ctx -> counter.increment());
        }
    }

    @Override
    public void handleEvents(List<HistoryEvent> events) {
        for (HistoryEvent event : events) {
            handleEvent(event);
        }
    }

    private Counter counterFor(HistoryEvent event) {
        String type = event.getEventType();
        if (type == null) {
            return null;
        }
        if (event instanceof HistoricProcessInstanceEventEntity instance) {
            Tag key = definitionKey(event);
            if (START.equals(type)) {
                return meters.counter(MetricNames.PROCESS_INSTANCES_STARTED, "Process instances started", key);
            }
            if (END.equals(type)) {
                String state = instance.getState() == null
                        ? "unknown"
                        : instance.getState().toLowerCase(Locale.ROOT);
                return meters.counter(
                        MetricNames.PROCESS_INSTANCES_ENDED,
                        "Process instances ended",
                        key,
                        Tag.of(MetricNames.TAG_STATE, state));
            }
        } else if (event instanceof HistoricIncidentEventEntity incident) {
            Tag[] tags = {
                Tag.of(MetricNames.TAG_INCIDENT_TYPE, incidentTypes.admit(incident.getIncidentType())),
                definitionKey(event)
            };
            if (INCIDENT_CREATE.equals(type)) {
                return meters.counter(MetricNames.INCIDENTS_RAISED, "Incidents raised", tags);
            }
            if (INCIDENT_RESOLVE.equals(type)) {
                return meters.counter(MetricNames.INCIDENTS_RESOLVED, "Incidents resolved", tags);
            }
            if (INCIDENT_DELETE.equals(type)) {
                return meters.counter(MetricNames.INCIDENTS_DELETED, "Incidents deleted", tags);
            }
        }
        return null;
    }

    private Tag definitionKey(HistoryEvent event) {
        return Tag.of(MetricNames.TAG_PROCESS_DEFINITION_KEY, definitionKeys.admit(event.getProcessDefinitionKey()));
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.connect;

import java.util.List;
import java.util.Objects;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.BpmnExecutionContext;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.connect.impl.AbstractConnector;
import org.operaton.connect.impl.AbstractRequestInvocation;
import org.operaton.connect.spi.ConnectorRequestInterceptor;
import org.operaton.connect.spi.ConnectorResponse;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.AiLlmPlugin;
import org.operaton.extensions.connector.aillm.LlmOperation;

/**
 * One LLM connector ({@code llm-prompt}, {@code llm-extract} or {@code llm-agent}). Connect creates
 * one instance per id and shares it between all executions; it holds no state besides the request
 * interceptors and finds the models through the engine the task runs in ({@link AiLlmPlugin}), so
 * it is thread-safe.
 */
public class LlmConnector extends AbstractConnector<LlmConnectorRequest, LlmConnectorResponse> {

    private final LlmOperation operation;

    public LlmConnector(LlmOperation operation) {
        super(Objects.requireNonNull(operation, "operation").connectorId());
        this.operation = operation;
    }

    public LlmOperation getOperation() {
        return operation;
    }

    @Override
    public LlmConnectorRequest createRequest() {
        return new LlmConnectorRequest(this);
    }

    @Override
    public ConnectorResponse execute(LlmConnectorRequest request) {
        Invocation invocation = new Invocation(request, requestInterceptors);
        try {
            return (ConnectorResponse) invocation.proceed();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AiLlmException(getId() + " failed: " + e.getMessage(), e);
        }
    }

    /** Runs the operation in the current engine context. */
    protected LlmConnectorResponse invoke(LlmConnectorRequest request) {
        ProcessEngineConfigurationImpl configuration = currentConfiguration();
        ExecutionEntity execution = currentExecution();
        return new LlmConnectorResponse(operation.execute(
                request.getRequestParameters(), AiLlmPlugin.serviceOf(configuration), execution, configuration));
    }

    private static ProcessEngineConfigurationImpl currentConfiguration() {
        try {
            return Context.getProcessEngineConfiguration();
        } catch (IllegalStateException e) {
            throw new AiLlmException("No process engine is active; the LLM connectors run inside a service task", e);
        }
    }

    private static ExecutionEntity currentExecution() {
        BpmnExecutionContext context = Context.getBpmnExecutionContext();
        return context == null ? null : context.getExecution();
    }

    private final class Invocation extends AbstractRequestInvocation<LlmConnectorRequest> {

        Invocation(LlmConnectorRequest request, List<ConnectorRequestInterceptor> interceptors) {
            super(request, request, interceptors);
        }

        @Override
        public Object invokeTarget() {
            return invoke(target);
        }
    }
}

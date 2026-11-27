/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.connector;

import java.util.Map;
import org.operaton.connect.impl.AbstractConnector;
import org.operaton.connect.impl.AbstractRequestInvocation;
import org.operaton.extensions.connector.s3.S3ConnectorException;
import org.operaton.extensions.connector.s3.internal.S3Action;

/**
 * A connector for one S3 operation. Stateless apart from its interceptors, so the single instance
 * that the Connect SPI hands to every service task is safe under the job executor.
 */
public final class S3Connector extends AbstractConnector<S3Request, S3Response> {

    private final S3Action action;

    S3Connector(S3Action action) {
        super(action.id());
        this.action = action;
    }

    @Override
    public S3Request createRequest() {
        return new S3Request(this);
    }

    @Override
    public S3Response execute(S3Request request) {
        AbstractRequestInvocation<S3Connector> invocation =
                new AbstractRequestInvocation<>(this, request, requestInterceptors) {
                    @Override
                    public Object invokeTarget() {
                        return action.execute(request.getRequestParameters());
                    }
                };
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) invocation.proceed();
            return new S3Response(result);
        } catch (S3ConnectorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new S3ConnectorException("Connector '" + connectorId + "' failed: " + e.getMessage(), e);
        }
    }
}

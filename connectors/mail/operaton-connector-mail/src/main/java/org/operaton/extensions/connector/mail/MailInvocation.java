/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import java.util.List;
import org.operaton.connect.impl.AbstractRequestInvocation;
import org.operaton.connect.spi.ConnectorRequest;
import org.operaton.connect.spi.ConnectorRequestInterceptor;

/** The target operation of a mail connector, wrapped by the connector's request interceptors. */
public final class MailInvocation<T> extends AbstractRequestInvocation<T> {

    /** The operation on the target. */
    @FunctionalInterface
    public interface Operation<T> {
        Object invoke(T target) throws Exception;
    }

    private final Operation<T> operation;

    public MailInvocation(
            T target,
            ConnectorRequest<?> request,
            List<ConnectorRequestInterceptor> interceptors,
            Operation<T> operation) {
        super(target, request, interceptors);
        this.operation = operation;
    }

    @Override
    public Object invokeTarget() throws Exception {
        return operation.invoke(target);
    }
}

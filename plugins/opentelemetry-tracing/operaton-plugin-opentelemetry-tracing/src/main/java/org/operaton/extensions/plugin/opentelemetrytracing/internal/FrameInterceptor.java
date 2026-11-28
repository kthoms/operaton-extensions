/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;

/**
 * Outermost interceptor (before the transaction): opens a {@link CommandFrame} and completes it with the
 * outcome of the whole command including the commit.
 */
public final class FrameInterceptor extends CommandInterceptor {

    private final Tracing tracing;
    private final boolean independent;

    public FrameInterceptor(Tracing tracing, boolean independent) {
        this.tracing = tracing;
        this.independent = independent;
    }

    @Override
    public <T> T execute(Command<T> command) {
        CommandFrame frame = tracing.frames.begin(independent);
        T result;
        try {
            result = next.execute(command);
        } catch (Throwable t) {
            tracing.frames.end(frame, t);
            throw t;
        }
        tracing.frames.end(frame, null);
        return result;
    }
}

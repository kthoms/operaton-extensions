/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import java.util.ArrayDeque;
import java.util.Deque;

/** The per-thread stack of {@link CommandFrame}s of one plugin instance. Thread-safe (thread-confined). */
final class CommandFrames {

    private final ThreadLocal<Deque<CommandFrame>> frames = ThreadLocal.withInitial(ArrayDeque::new);

    CommandFrame begin(boolean independent) {
        CommandFrame frame = new CommandFrame(independent);
        frames.get().push(frame);
        return frame;
    }

    void end(CommandFrame frame, Throwable failure) {
        Deque<CommandFrame> stack = frames.get();
        if (stack.peek() != frame) {
            throw new IllegalStateException("command frames are out of order on thread " + Thread.currentThread());
        }
        stack.pop();
        try {
            frame.complete(failure, stack.peek());
        } finally {
            if (stack.isEmpty()) {
                frames.remove();
            }
        }
    }

    CommandFrame current() {
        CommandFrame frame = frames.get().peek();
        if (frame == null) {
            frames.remove();
            throw new IllegalStateException("The OpenTelemetry tracing listener ran outside a traced engine command;"
                    + " the plugin's command interceptor is not installed in this engine's command chain");
        }
        return frame;
    }
}

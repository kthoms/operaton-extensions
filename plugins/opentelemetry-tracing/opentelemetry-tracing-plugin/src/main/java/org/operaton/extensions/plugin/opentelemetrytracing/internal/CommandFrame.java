/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bookkeeping of one command execution on one thread: the scopes made current, the spans owned by the
 * command and the actions to run once the command is known to have committed or failed. Confined to the
 * thread that runs the command; not thread-safe by design.
 */
final class CommandFrame {

    private record ScopeEntry(String key, boolean job, Scope scope) {}

    private final boolean independent;
    private final Deque<ScopeEntry> scopes = new ArrayDeque<>();
    private final List<Runnable> onCommit = new ArrayList<>();
    private final List<Runnable> onRollback = new ArrayList<>();
    private final List<Span> ownedSpans = new ArrayList<>();

    CommandFrame(boolean independent) {
        this.independent = independent;
    }

    boolean isIndependent() {
        return independent;
    }

    void pushScope(String key, boolean job, Scope scope) {
        scopes.push(new ScopeEntry(key, job, scope));
    }

    /** Closes activity scopes on top of the stack until the top is {@code parentKey} (or a job scope). */
    void pruneTo(String parentKey) {
        while (!scopes.isEmpty()) {
            ScopeEntry top = scopes.peek();
            if (top.job() || top.key().equals(parentKey)) {
                return;
            }
            scopes.pop().scope().close();
        }
    }

    /** Closes scopes down to and including {@code key}; does nothing if this frame never pushed it. */
    void closeDownTo(String key) {
        if (scopes.stream().noneMatch(e -> e.key().equals(key))) {
            return;
        }
        while (!scopes.isEmpty()) {
            ScopeEntry top = scopes.pop();
            top.scope().close();
            if (top.key().equals(key)) {
                return;
            }
        }
    }

    void onCommit(Runnable action) {
        onCommit.add(action);
    }

    void onRollback(Runnable action) {
        onRollback.add(action);
    }

    /** A span that ends with this command, with an error status if the command fails. */
    void ownSpan(Span span) {
        ownedSpans.add(span);
    }

    /** Called when the command and its transaction are finished; {@code failure} is null on success. */
    void complete(Throwable failure, CommandFrame enclosing) {
        while (!scopes.isEmpty()) {
            scopes.pop().scope().close();
        }
        for (Span span : ownedSpans) {
            if (failure != null) {
                span.recordException(failure);
                span.setStatus(StatusCode.ERROR, String.valueOf(failure.getMessage()));
            }
            span.end();
        }
        if (failure != null) {
            for (int i = onRollback.size() - 1; i >= 0; i--) {
                onRollback.get(i).run();
            }
        } else if (enclosing != null && !independent) {
            // joins the transaction of the enclosing command: its outcome decides
            enclosing.onCommit.addAll(onCommit);
            enclosing.onRollback.addAll(onRollback);
        } else {
            onCommit.forEach(Runnable::run);
        }
    }
}

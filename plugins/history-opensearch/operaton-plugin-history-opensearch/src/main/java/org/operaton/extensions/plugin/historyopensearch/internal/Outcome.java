/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.util.List;

/**
 * The result of writing a group of history documents.
 *
 * @param written document operations OpenSearch accepted
 * @param failures document operations that were not written, with the reason
 */
public record Outcome(long written, List<Failure> failures) {

    /** A document operation that could not be written. {@code description} never contains field values. */
    public record Failure(String description, Throwable cause) {}

    /** Whether every document operation was written. */
    public boolean succeeded() {
        return failures.isEmpty();
    }
}

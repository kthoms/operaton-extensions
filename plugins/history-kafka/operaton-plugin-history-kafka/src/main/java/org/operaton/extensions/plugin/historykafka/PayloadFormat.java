/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

/** Wire format of a published history event. */
public enum PayloadFormat {
    /** Plain JSON envelope: {@code id, type, category, event, time, source, data}. */
    JSON,
    /** CloudEvents 1.0 in structured JSON content mode ({@code application/cloudevents+json}). */
    CLOUDEVENTS
}

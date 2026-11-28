/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import org.operaton.bpm.engine.ProcessEngineException;

/** A history event could not be converted or published to Kafka. */
public class HistoryKafkaException extends ProcessEngineException {

    private static final long serialVersionUID = 1L;

    public HistoryKafkaException(String message) {
        super(message);
    }

    public HistoryKafkaException(String message, Throwable cause) {
        super(message, cause);
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

/** What to do when an after-commit send fails. */
public enum DeliveryFailureMode {
    /** Log at ERROR and continue: the committed process state is not affected. */
    LOG,
    /** Log at ERROR and throw to the caller of the engine command although the transaction is committed. */
    THROW;

    public DeliveryFailureHandler handler() {
        return this == THROW ? DeliveryFailureHandler.throwing() : DeliveryFailureHandler.logging();
    }
}

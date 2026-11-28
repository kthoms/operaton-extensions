/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Marker of a {@link TypedValue} that is encrypted whatever the variable is called. Created with
 * {@link EncryptedVariables#encrypted(TypedValue)}; the value is an ordinary typed value of its
 * own type (a {@code StringValue} stays a {@code StringValue}), the marker only exists while the
 * value is being written. A value read back from the engine is never marked.
 */
public interface EncryptedValue extends TypedValue {}

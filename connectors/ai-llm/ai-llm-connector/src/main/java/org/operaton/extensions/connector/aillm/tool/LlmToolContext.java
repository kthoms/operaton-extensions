/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.tool;

import org.operaton.extensions.connector.aillm.VariableSource;

/**
 * What a tool call can see of the calling process instance.
 *
 * @param variables the variables of the calling execution
 */
public record LlmToolContext(VariableSource variables) {}

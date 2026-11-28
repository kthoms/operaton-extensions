/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Type-safe access to process variables: a {@link org.operaton.extensions.plugin.typedvariables.VariableFactory}
 * declares the name and the type of a variable once; delegates, listeners, external task workers and tests read and
 * write it through that declaration.
 *
 * <p>Start with {@link org.operaton.extensions.plugin.typedvariables.TypedVariables}. The extension has no engine
 * plugin to register; it only needs {@code operaton-engine} on the classpath.
 */
package org.operaton.extensions.plugin.typedvariables;

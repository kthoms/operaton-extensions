/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.delegate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.connector.s3.S3ConnectorException;
import org.operaton.extensions.connector.s3.S3Download;
import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.internal.S3Action;

/**
 * Base of the S3 delegates, the {@link JavaDelegate} alternative to the connectors. They take the
 * same parameters as the connector of the same name, read from the service task's <em>input
 * parameters</em> ({@code operaton:inputOutput}), and write the response parameters as process
 * variables named {@code <resultPrefix><Name>} (default prefix {@code s3}, so {@code s3Etag},
 * {@code s3VersionId}, ...). Only text, number and boolean results become variables; lists and maps
 * are available as their {@code ...Json} text.
 *
 * <p>Two parameters exist only for delegates, because a delegate can use typed variables directly:
 * {@code contentVariable} (put: the variable to upload, a file variable keeps its file name and
 * content type) and {@code targetVariable} (get: the file variable that receives the object,
 * default {@code <resultPrefix>Content}).
 *
 * <p>Stateless and therefore safe as a singleton bean or when instantiated per class name. The
 * no-argument constructor finds the stores through the engine that runs the task.
 */
public abstract class AbstractS3Delegate implements JavaDelegate {

    private final S3Action action;
    private final S3StoreRegistry registry;

    protected AbstractS3Delegate(S3Action action, S3StoreRegistry registry) {
        this.action = action;
        this.registry = registry;
    }

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> parameters = new HashMap<>(execution.getVariablesLocal());
        String contentVariable = text(parameters.get("contentVariable"));
        if (contentVariable != null) {
            TypedValue typed = execution.getVariableTyped(contentVariable);
            if (typed == null) {
                throw new S3ConnectorException(action.id() + ": the content variable '" + contentVariable
                        + "' does not exist in " + execution.getId());
            }
            parameters.put("content", typed instanceof FileValue ? typed : typed.getValue());
        }
        Map<String, Object> result =
                registry != null ? action.execute(registry, parameters) : action.execute(parameters);

        String prefix = text(parameters.get("resultPrefix")) != null ? text(parameters.get("resultPrefix")) : "s3";
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof S3Download download) {
                String target = text(parameters.get("targetVariable")) != null
                        ? text(parameters.get("targetVariable"))
                        : prefix + "Content";
                execution.setVariable(target, download.getFileValue());
            } else if (value == null
                    || value instanceof CharSequence
                    || value instanceof Number
                    || value instanceof Boolean) {
                execution.setVariable(variableName(prefix, entry.getKey()), value);
            }
        }
    }

    private static String variableName(String prefix, String name) {
        return prefix + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}

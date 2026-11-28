/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.lang.reflect.Proxy;
import org.operaton.bpm.client.task.ExternalTask;
import org.operaton.bpm.engine.variable.VariableMap;

/** Stubs of the external task client's {@link ExternalTask}: only the variable accessors and the ids are answered. */
final class ExternalTaskStubs {

    private ExternalTaskStubs() {}

    /**
     * Returns a task that carries the given variables, as the client delivers them for the subscribed names.
     *
     * @param variables the fetched variables
     * @return the stub
     */
    static ExternalTask withVariables(VariableMap variables) {
        return (ExternalTask) Proxy.newProxyInstance(
                ExternalTaskStubs.class.getClassLoader(),
                new Class<?>[] {ExternalTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVariableTyped":
                            return variables.getValueTyped((String) args[0]);
                        case "getVariable":
                            return variables.get(args[0]);
                        case "getAllVariablesTyped":
                        case "getAllVariables":
                            return variables;
                        case "getId":
                            return "task-1";
                        case "getTopicName":
                            return "price-order";
                        case "toString":
                            return "ExternalTaskStub";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException("ExternalTask stub does not answer " + method);
                    }
                });
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc.connect;

import java.util.List;
import java.util.concurrent.Callable;
import org.operaton.connect.impl.AbstractRequestInvocation;
import org.operaton.connect.spi.ConnectorRequest;
import org.operaton.connect.spi.ConnectorRequestInterceptor;

/** Runs a JDBC call through the connector's request interceptor chain. */
final class JdbcCall extends AbstractRequestInvocation<Callable<Object>> {

    JdbcCall(Callable<Object> target, ConnectorRequest<?> request, List<ConnectorRequestInterceptor> interceptors) {
        super(target, request, interceptors);
    }

    @Override
    public Object invokeTarget() throws Exception {
        return target.call();
    }
}

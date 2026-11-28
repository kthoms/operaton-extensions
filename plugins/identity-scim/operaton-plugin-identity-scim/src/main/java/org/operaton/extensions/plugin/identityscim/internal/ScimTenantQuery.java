/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimTenantQuery.java (Apache-2.0)
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.util.List;
import org.operaton.bpm.engine.identity.Tenant;
import org.operaton.bpm.engine.impl.Page;
import org.operaton.bpm.engine.impl.TenantQueryImpl;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;

/**
 * A tenant query for a SCIM identity provider. SCIM has no tenants, so the answer is always that
 * there are none: nothing the engine asks for exists, which is not a failure to read them.
 */
public final class ScimTenantQuery extends TenantQueryImpl {

    private static final long serialVersionUID = 1L;

    /** Creates a query that is executed in the current command context. */
    public ScimTenantQuery() {
        super();
    }

    /**
     * Creates a query that runs in its own command.
     *
     * @param commandExecutor the executor
     */
    public ScimTenantQuery(CommandExecutor commandExecutor) {
        super(commandExecutor);
    }

    @Override
    public long executeCount(CommandContext commandContext) {
        return 0;
    }

    @Override
    public List<Tenant> executeList(CommandContext commandContext, Page page) {
        return List.of();
    }
}

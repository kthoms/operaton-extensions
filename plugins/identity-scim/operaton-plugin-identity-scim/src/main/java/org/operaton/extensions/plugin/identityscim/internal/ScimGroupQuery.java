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
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimGroupQuery.java (Apache-2.0)
 * Changes: the criteria are handed over as a value object, unsupported criteria (tenant, potential
 * starter) fail instead of being ignored, ordering is translated here.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.impl.Direction;
import org.operaton.bpm.engine.impl.GroupQueryImpl;
import org.operaton.bpm.engine.impl.GroupQueryProperty;
import org.operaton.bpm.engine.impl.Page;
import org.operaton.bpm.engine.impl.QueryOrderingProperty;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;

/** A group query that is answered by the SCIM server. */
public final class ScimGroupQuery extends GroupQueryImpl {

    private static final long serialVersionUID = 1L;

    /** Creates a query that is executed in the current command context. */
    public ScimGroupQuery() {
        super();
    }

    /**
     * Creates a query that runs in its own command.
     *
     * @param commandExecutor the executor
     */
    public ScimGroupQuery(CommandExecutor commandExecutor) {
        super(commandExecutor);
    }

    @Override
    public long executeCount(CommandContext commandContext) {
        return ScimIdentityProviderSession.of(commandContext).countGroups(commandContext, this);
    }

    @Override
    public List<Group> executeList(CommandContext commandContext, Page page) {
        return ScimIdentityProviderSession.of(commandContext).findGroups(commandContext, this, page);
    }

    /**
     * Translates the query's criteria.
     *
     * @return the criteria
     * @throws BadUserRequestException for criteria SCIM cannot answer
     */
    GroupCriteria criteria() {
        if (getTenantId() != null) {
            throw new BadUserRequestException(
                    "Querying groups by tenant is not supported by the SCIM identity provider: it has no tenants");
        }
        if (procDefId != null) {
            throw new BadUserRequestException(
                    "Querying potential starters is not supported by the SCIM identity provider: it is based on engine authorizations");
        }
        return new GroupCriteria(
                getId(),
                getIds() == null ? null : Arrays.asList(getIds()),
                getName(),
                getNameLike(),
                getType(),
                getUserId());
    }

    /**
     * Translates the query's ordering.
     *
     * @return the ordering, most significant first
     */
    List<SortSpec> sort() {
        List<SortSpec> result = new ArrayList<>();
        List<QueryOrderingProperty> properties = getOrderingProperties();
        if (properties == null) {
            return result;
        }
        for (QueryOrderingProperty property : properties) {
            String name = property.getQueryProperty().getName();
            SortSpec.Field field;
            if (GroupQueryProperty.GROUP_ID.getName().equals(name)) {
                field = SortSpec.Field.ID;
            } else if (GroupQueryProperty.NAME.getName().equals(name)) {
                field = SortSpec.Field.NAME;
            } else if (GroupQueryProperty.TYPE.getName().equals(name)) {
                field = SortSpec.Field.TYPE;
            } else {
                throw new BadUserRequestException(
                        "Ordering groups by '" + name + "' is not supported by the SCIM identity provider");
            }
            result.add(new SortSpec(field, Direction.ASCENDING.equals(property.getDirection())));
        }
        return result;
    }
}

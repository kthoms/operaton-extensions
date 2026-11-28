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
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimIdentityProviderReadOnly.java (Apache-2.0)
 * Ported: the ReadOnlyIdentityProvider contract (user, group and tenant lookups, native queries
 * rejected, no tenants) and the authorization rules (an authenticated user always sees itself, else
 * READ on USER or GROUP is required, switchable by configuration).
 * Changes: the SCIM logic lives in the shared directory; users and groups are filtered by authorization
 * before paging so counts and pages agree; the password check is delegated to a checker instead of
 * answering true for every existing user.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.operaton.bpm.engine.authorization.Permissions.READ;
import static org.operaton.bpm.engine.authorization.Resources.GROUP;
import static org.operaton.bpm.engine.authorization.Resources.USER;

import java.util.List;
import java.util.function.Predicate;
import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.GroupQuery;
import org.operaton.bpm.engine.identity.NativeUserQuery;
import org.operaton.bpm.engine.identity.Tenant;
import org.operaton.bpm.engine.identity.TenantQuery;
import org.operaton.bpm.engine.identity.User;
import org.operaton.bpm.engine.identity.UserQuery;
import org.operaton.bpm.engine.impl.Page;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.GroupEntity;
import org.operaton.bpm.engine.impl.persistence.entity.UserEntity;
import org.operaton.extensions.plugin.identityscim.ScimSettings;

/**
 * The engine's read-only identity provider session, answered by the SCIM server. One lightweight
 * instance per engine command; all state lives in the shared {@link ScimDirectory}.
 */
public final class ScimIdentityProviderSession implements ReadOnlyIdentityProvider {

    private final ScimSettings settings;
    private final ScimDirectory directory;
    private final ScimPasswordChecker passwordChecker;

    ScimIdentityProviderSession(ScimSettings settings, ScimDirectory directory, ScimPasswordChecker passwordChecker) {
        this.settings = settings;
        this.directory = directory;
        this.passwordChecker = passwordChecker;
    }

    /**
     * Returns the SCIM session of a command.
     *
     * @param commandContext the command context
     * @return the session
     * @throws IdentityProviderException when the engine's read-only identity provider is not the SCIM one
     */
    static ScimIdentityProviderSession of(CommandContext commandContext) {
        ReadOnlyIdentityProvider provider = commandContext.getReadOnlyIdentityProvider();
        if (provider instanceof ScimIdentityProviderSession session) {
            return session;
        }
        throw new IdentityProviderException("A SCIM query was executed but the engine's identity provider is "
                + (provider == null ? "missing" : provider.getClass().getName()));
    }

    // ---- users

    @Override
    public User findUserById(String userId) {
        return createUserQuery(Context.getCommandContext()).userId(userId).singleResult();
    }

    @Override
    public UserQuery createUserQuery() {
        return new ScimUserQuery(Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
    }

    @Override
    public UserQuery createUserQuery(CommandContext commandContext) {
        return new ScimUserQuery();
    }

    @Override
    public NativeUserQuery createNativeUserQuery() {
        throw new BadUserRequestException("Native user queries are not supported by the SCIM identity provider");
    }

    List<User> findUsers(CommandContext commandContext, ScimUserQuery query, Page page) {
        int first = Math.max(page == null ? query.getFirstResult() : page.getFirstResult(), 0);
        int max = page == null ? query.getMaxResults() : page.getMaxResults();
        return directory.users(query.criteria(), query.sort(), first, max, userVisibility(commandContext)).stream()
                .<User>map(ScimIdentityProviderSession::toEntity)
                .toList();
    }

    long countUsers(CommandContext commandContext, ScimUserQuery query) {
        return directory.countUsers(query.criteria(), userVisibility(commandContext));
    }

    private Predicate<ScimUser> userVisibility(CommandContext commandContext) {
        if (!authorizationApplies(commandContext)) {
            return null;
        }
        return user -> isAuthenticatedUser(commandContext, user.id())
                || commandContext.getAuthorizationManager().isAuthorized(READ, USER, user.id());
    }

    private static UserEntity toEntity(ScimUser user) {
        UserEntity entity = new UserEntity(user.id());
        entity.setFirstName(user.firstName());
        entity.setLastName(user.lastName());
        entity.setEmail(user.email());
        return entity;
    }

    // ---- groups

    @Override
    public Group findGroupById(String groupId) {
        return createGroupQuery(Context.getCommandContext()).groupId(groupId).singleResult();
    }

    @Override
    public GroupQuery createGroupQuery() {
        return new ScimGroupQuery(Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
    }

    @Override
    public GroupQuery createGroupQuery(CommandContext commandContext) {
        return new ScimGroupQuery();
    }

    List<Group> findGroups(CommandContext commandContext, ScimGroupQuery query, Page page) {
        int first = Math.max(page == null ? query.getFirstResult() : page.getFirstResult(), 0);
        int max = page == null ? query.getMaxResults() : page.getMaxResults();
        return directory.groups(query.criteria(), query.sort(), first, max, groupVisibility(commandContext)).stream()
                .<Group>map(ScimIdentityProviderSession::toEntity)
                .toList();
    }

    long countGroups(CommandContext commandContext, ScimGroupQuery query) {
        return directory.countGroups(query.criteria(), groupVisibility(commandContext));
    }

    private Predicate<ScimGroup> groupVisibility(CommandContext commandContext) {
        if (!authorizationApplies(commandContext)) {
            return null;
        }
        return group -> commandContext.getAuthorizationManager().isAuthorized(READ, GROUP, group.id());
    }

    private static GroupEntity toEntity(ScimGroup group) {
        GroupEntity entity = new GroupEntity(group.id());
        entity.setName(group.name());
        entity.setType(ScimDirectory.GROUP_TYPE);
        return entity;
    }

    // ---- tenants

    /** SCIM has no tenants: there is none to find. */
    @Override
    public Tenant findTenantById(String id) {
        return null;
    }

    @Override
    public TenantQuery createTenantQuery() {
        return new ScimTenantQuery(Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
    }

    @Override
    public TenantQuery createTenantQuery(CommandContext commandContext) {
        return new ScimTenantQuery();
    }

    // ---- password

    @Override
    public boolean checkPassword(String userId, String password) {
        return passwordChecker.check(userId, password);
    }

    // ---- authorization

    private boolean authorizationApplies(CommandContext commandContext) {
        return settings.authorizationCheckEnabled()
                && commandContext.getProcessEngineConfiguration().isAuthorizationEnabled()
                && commandContext.isAuthorizationCheckEnabled()
                && commandContext.getAuthenticatedUserId() != null;
    }

    private static boolean isAuthenticatedUser(CommandContext commandContext, String userId) {
        return userId != null && userId.equalsIgnoreCase(commandContext.getAuthenticatedUserId());
    }
}

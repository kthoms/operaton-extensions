/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.bpm.engine.authorization.Authorization.ANY;
import static org.operaton.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.operaton.bpm.engine.authorization.Permissions.READ;
import static org.operaton.bpm.engine.authorization.Resources.GROUP;
import static org.operaton.bpm.engine.authorization.Resources.USER;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.authorization.Authorization;
import org.operaton.bpm.engine.authorization.Resource;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.operaton.bpm.engine.impl.plugin.AdministratorAuthorizationPlugin;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

/**
 * Authorization checks with identities that live in SCIM: what an authenticated user sees of the
 * directory depends on READ authorizations, which can be granted to SCIM users and SCIM groups.
 */
class ScimAuthorizationEngineTest {

    static final FakeScimServer SCIM = Scim.seededServer();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                cfg.setAuthorizationEnabled(true);
                cfg.getProcessEnginePlugins().add(Scim.plugin(SCIM));
                AdministratorAuthorizationPlugin administrators = new AdministratorAuthorizationPlugin();
                administrators.setAdministratorGroupName("operaton-admin");
                cfg.getProcessEnginePlugins().add(administrators);
            })
            .build();

    @AfterAll
    static void stopServer() {
        SCIM.close();
    }

    @AfterEach
    void cleanUp() {
        engine.getIdentityService().clearAuthentication();
        engine.getAuthorizationService().createAuthorizationQuery().list().stream()
                .filter(a -> !"operaton-admin".equals(a.getGroupId()))
                .forEach(a -> engine.getAuthorizationService().deleteAuthorization(a.getId()));
    }

    private List<String> groupsOf(String userId) {
        return engine.getIdentityService().createGroupQuery().groupMember(userId).list().stream()
                .map(Group::getId)
                .toList();
    }

    private void authenticate(String userId) {
        engine.getIdentityService().setAuthentication(userId, groupsOf(userId));
    }

    private void grantRead(String userId, String groupId, Resource resource, String resourceId) {
        Authorization authorization = engine.getAuthorizationService().createNewAuthorization(AUTH_TYPE_GRANT);
        if (userId != null) {
            authorization.setUserId(userId);
        } else {
            authorization.setGroupId(groupId);
        }
        authorization.setResource(resource);
        authorization.setResourceId(resourceId);
        authorization.addPermission(READ);
        engine.getAuthorizationService().saveAuthorization(authorization);
    }

    private List<String> visibleUsers() {
        return engine.getIdentityService().createUserQuery().orderByUserId().asc().list().stream()
                .map(User::getId)
                .toList();
    }

    private List<String> visibleGroups() {
        return engine.getIdentityService().createGroupQuery().orderByGroupId().asc().list().stream()
                .map(Group::getId)
                .toList();
    }

    @Test
    void withoutAnAuthenticatedUserNothingIsRestricted() {
        assertThat(visibleUsers()).containsExactly("alice", "bob", "carol", "dave");
        assertThat(visibleGroups()).hasSize(4);
    }

    @Test
    void anUnprivilegedUserSeesOnlyItself() {
        authenticate("alice");

        assertThat(visibleUsers()).containsExactly("alice");
        assertThat(engine.getIdentityService().createUserQuery().count()).isEqualTo(1);
        assertThat(visibleGroups()).isEmpty();
        assertThat(engine.getIdentityService().createGroupQuery().count()).isZero();
        assertThat(engine.getIdentityService().createUserQuery().userId("bob").singleResult())
                .isNull();
    }

    @Test
    void readAuthorizationsOnUsersAndGroupsWidenWhatIsVisible() {
        grantRead("alice", null, USER, "bob");
        grantRead("alice", null, GROUP, "clerks");
        authenticate("alice");

        assertThat(visibleUsers()).containsExactly("alice", "bob");
        assertThat(visibleGroups()).containsExactly("clerks");
        assertThat(engine.getIdentityService().createUserQuery().userId("bob").singleResult())
                .isNotNull();
        assertThat(engine.getIdentityService().createUserQuery().userId("carol").singleResult())
                .isNull();
    }

    @Test
    void pagesAndCountsAgreeWithWhatTheUserMaySee() {
        grantRead("alice", null, USER, "carol");
        grantRead("alice", null, USER, "dave");
        authenticate("alice");

        assertThat(engine.getIdentityService().createUserQuery().count()).isEqualTo(3);
        assertThat(engine.getIdentityService()
                        .createUserQuery()
                        .orderByUserId()
                        .asc()
                        .listPage(1, 1))
                .extracting(User::getId)
                .containsExactly("carol");
        assertThat(engine.getIdentityService()
                        .createUserQuery()
                        .orderByUserId()
                        .asc()
                        .listPage(2, 5))
                .extracting(User::getId)
                .containsExactly("dave");
    }

    @Test
    void authorizationsGrantedToAScimGroupApplyToItsMembers() {
        grantRead(null, "approvers", USER, ANY);
        authenticate("carol");
        assertThat(visibleUsers()).as("carol is an approver").containsExactly("alice", "bob", "carol", "dave");
        engine.getIdentityService().clearAuthentication();

        authenticate("bob");
        assertThat(visibleUsers()).as("bob is not").containsExactly("bob");
    }

    @Test
    void membersOfTheAdministratorGroupSeeEverything() {
        authenticate("dave");

        assertThat(groupsOf("dave")).contains("operaton-admin");
        assertThat(visibleUsers()).containsExactly("alice", "bob", "carol", "dave");
        assertThat(visibleGroups()).containsExactly("all-staff", "approvers", "clerks", "operaton-admin");
        assertThat(engine.getIdentityService()
                        .createUserQuery()
                        .memberOfGroup("approvers")
                        .count())
                .isEqualTo(2);
    }

    @Test
    void theMembersOfAGroupAreFilteredByAuthorizationToo() {
        grantRead("alice", null, USER, "carol");
        authenticate("alice");

        assertThat(engine.getIdentityService()
                        .createUserQuery()
                        .memberOfGroup("approvers")
                        .orderByUserId()
                        .asc()
                        .list())
                .extracting(User::getId)
                .containsExactly("alice", "carol");
        assertThat(engine.getIdentityService()
                        .createUserQuery()
                        .memberOfGroup("clerks")
                        .list())
                .isEmpty();
    }

    @Test
    void authorizationServiceDecisionsUseTheGroupsFromScim() {
        grantRead(null, "clerks", GROUP, "clerks");

        assertThat(engine.getAuthorizationService().isUserAuthorized("bob", groupsOf("bob"), READ, GROUP, "clerks"))
                .isTrue();
        assertThat(engine.getAuthorizationService().isUserAuthorized("alice", groupsOf("alice"), READ, GROUP, "clerks"))
                .isFalse();
    }
}

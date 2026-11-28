/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

/** The engine resolves users, groups and task candidates from the SCIM server. */
class ScimIdentityProviderEngineTest {

    static final FakeScimServer SCIM = Scim.seededServer();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> cfg.getProcessEnginePlugins().add(Scim.plugin(SCIM)))
            .build();

    @AfterAll
    static void stopServer() {
        SCIM.close();
    }

    @BeforeEach
    void reset() {
        SCIM.stopFailing();
        SCIM.clearRequests();
    }

    private IdentityService identity() {
        return engine.getIdentityService();
    }

    // ---- users

    @Test
    void queriesUsersWithMappingFilterOrderAndPaging() {
        assertThat(identity().createUserQuery().orderByUserId().asc().list())
                .extracting(User::getId)
                .containsExactly("alice", "bob", "carol", "dave");
        assertThat(identity().createUserQuery().count()).isEqualTo(4);

        User alice = identity().createUserQuery().userId("alice").singleResult();
        assertThat(alice.getFirstName()).isEqualTo("Alice");
        assertThat(alice.getLastName()).isEqualTo("Anderson");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        assertThat(alice.getPassword()).as("passwords never come from SCIM").isNull();

        assertThat(identity().createUserQuery().orderByUserLastName().desc().listPage(1, 2))
                .extracting(User::getId)
                .containsExactly("carol", "bob");
        assertThat(identity()
                        .createUserQuery()
                        .userIdIn("bob", "dave", "nobody")
                        .orderByUserId()
                        .asc()
                        .list())
                .extracting(User::getId)
                .containsExactly("bob", "dave");
        assertThat(identity().createUserQuery().userFirstNameLike("%ar%").list())
                .extracting(User::getId)
                .containsExactly("carol");
        assertThat(identity()
                        .createUserQuery()
                        .userLastName("Brown")
                        .singleResult()
                        .getId())
                .isEqualTo("bob");
        assertThat(identity().createUserQuery().userEmailLike("%@example.com").count())
                .isEqualTo(4);
        assertThat(identity().createUserQuery().userId("nobody").singleResult()).isNull();
    }

    @Test
    void queriesTheMembersOfAGroup() {
        assertThat(identity()
                        .createUserQuery()
                        .memberOfGroup("approvers")
                        .orderByUserId()
                        .asc()
                        .list())
                .extracting(User::getId)
                .containsExactly("alice", "carol");
        assertThat(identity().createUserQuery().memberOfGroup("all-staff").count())
                .isEqualTo(4);
        assertThat(identity()
                        .createUserQuery()
                        .memberOfGroup("clerks")
                        .userLastName("Anderson")
                        .list())
                .isEmpty();
    }

    // ---- groups

    @Test
    void queriesGroupsAndTheGroupsOfAUser() {
        assertThat(identity().createGroupQuery().orderByGroupName().asc().list())
                .extracting(Group::getId)
                .containsExactly("all-staff", "approvers", "clerks", "operaton-admin");
        Group approvers = identity().createGroupQuery().groupId("approvers").singleResult();
        assertThat(approvers.getName()).isEqualTo("approvers");
        assertThat(approvers.getType()).isEqualTo("SCIM");

        assertThat(identity()
                        .createGroupQuery()
                        .groupMember("alice")
                        .orderByGroupId()
                        .asc()
                        .list())
                .extracting(Group::getId)
                .containsExactly("all-staff", "approvers");
        assertThat(identity().createGroupQuery().groupMember("dave").count()).isEqualTo(2);
        assertThat(identity().createGroupQuery().groupMember("nobody").list()).isEmpty();
        assertThat(identity()
                        .createGroupQuery()
                        .groupNameLike("%s")
                        .orderByGroupName()
                        .asc()
                        .list())
                .extracting(Group::getId)
                .containsExactly("approvers", "clerks");
        assertThat(identity()
                        .createGroupQuery()
                        .groupIdIn("clerks", "operaton-admin")
                        .count())
                .isEqualTo(2);
        assertThat(identity().createGroupQuery().groupType("SCIM").count()).isEqualTo(4);
        assertThat(identity().createGroupQuery().groupType("LDAP").count()).isZero();
    }

    // ---- what the provider does not do

    @Test
    void theProviderIsReadOnly() {
        assertThat(identity().isReadOnly()).isTrue();
        assertThatThrownBy(() -> identity().saveUser(identity().newUser("mallory")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> identity().createMembership("alice", "clerks"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unsupportedQueriesFailLoudly() {
        assertThatThrownBy(() -> identity().createNativeUserQuery())
                .isInstanceOf(BadUserRequestException.class)
                .hasMessageContaining("Native user queries");
        assertThatThrownBy(
                        () -> identity().createUserQuery().memberOfTenant("t1").list())
                .isInstanceOf(BadUserRequestException.class)
                .hasMessageContaining("tenant");
        assertThatThrownBy(() ->
                        identity().createUserQuery().potentialStarter("proc").list())
                .isInstanceOf(BadUserRequestException.class)
                .hasMessageContaining("potential starters");
        assertThatThrownBy(
                        () -> identity().createGroupQuery().memberOfTenant("t1").list())
                .isInstanceOf(BadUserRequestException.class);
        assertThatThrownBy(() ->
                        identity().createUserQuery().userFirstNameLike("a%b").list())
                .isInstanceOf(BadUserRequestException.class)
                .hasMessageContaining("'%' is only allowed");
    }

    @Test
    void thereAreNoTenants() {
        assertThat(identity().createTenantQuery().list()).isEmpty();
        assertThat(identity().createTenantQuery().count()).isZero();
        assertThat(identity().createTenantQuery().tenantId("any").singleResult())
                .isNull();
    }

    @Test
    void checkingPasswordsIsNotSupportedByDefault() {
        assertThatThrownBy(() -> identity().checkPassword("alice", "secret"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("not supported");
    }

    // ---- task assignment

    @Test
    @Deployment(resources = "bpmn/approval.bpmn20.xml")
    void taskAssignmentResolvesCandidateGroupsAndUsersFromScim() {
        TaskService tasks = engine.getTaskService();
        engine.getRuntimeService().startProcessInstanceByKey("approval");

        assertThat(tasks.createTaskQuery().taskCandidateUser("alice").list())
                .extracting(Task::getName)
                .containsExactly("Approve request");
        assertThat(tasks.createTaskQuery().taskCandidateUser("carol").count()).isEqualTo(1);
        assertThat(tasks.createTaskQuery().taskCandidateUser("bob").count())
                .as("bob is a clerk")
                .isZero();
        assertThat(tasks.createTaskQuery().taskCandidateUser("dave").count()).isZero();
        assertThat(tasks.createTaskQuery().taskCandidateGroup("approvers").count())
                .isEqualTo(1);

        Task approve = tasks.createTaskQuery().taskCandidateUser("alice").singleResult();
        tasks.claim(approve.getId(), "alice");
        tasks.complete(approve.getId());

        assertThat(tasks.createTaskQuery().taskCandidateUser("alice").count()).isZero();
        assertThat(tasks.createTaskQuery().taskCandidateUser("bob").list())
                .extracting(Task::getName)
                .as("candidate user bob and candidate group clerks, both resolved")
                .containsExactly("Archive request");
        assertThat(tasks.createTaskQuery().taskCandidateUser("dave").count()).isZero();
    }

    @Test
    @Deployment(resources = "bpmn/approval.bpmn20.xml")
    void aRealignedGroupChangesWhoSeesTheTaskOnceTheCacheExpires() {
        TaskService tasks = engine.getTaskService();
        engine.getRuntimeService().startProcessInstanceByKey("approval");
        assertThat(tasks.createTaskQuery().taskCandidateUser("dave").count()).isZero();
        SCIM.group("g-1", "approvers", "u-1", "u-3", "u-4");
        engine.getProcessEngineConfiguration().getProcessEnginePlugins().stream()
                .filter(ScimIdentityProviderPlugin.class::isInstance)
                .map(ScimIdentityProviderPlugin.class::cast)
                .forEach(ScimIdentityProviderPlugin::close);
        try {
            assertThat(tasks.createTaskQuery().taskCandidateUser("dave").count())
                    .isEqualTo(1);
        } finally {
            SCIM.group("g-1", "approvers", "u-1", "u-3");
            engine.getProcessEngineConfiguration().getProcessEnginePlugins().stream()
                    .filter(ScimIdentityProviderPlugin.class::isInstance)
                    .map(ScimIdentityProviderPlugin.class::cast)
                    .forEach(ScimIdentityProviderPlugin::close);
        }
    }

    // ---- failures reach the caller

    @Test
    void anUnavailableScimServerFailsTheQueryInsteadOfReturningNothing() {
        SCIM.failWith(503, "{\"detail\":\"maintenance\"}");
        engine.getProcessEngineConfiguration().getProcessEnginePlugins().stream()
                .filter(ScimIdentityProviderPlugin.class::isInstance)
                .map(ScimIdentityProviderPlugin.class::cast)
                .forEach(ScimIdentityProviderPlugin::close);

        assertThatThrownBy(() -> identity().createUserQuery().userId("alice").singleResult())
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 503")
                .hasMessageContaining("maintenance");
        assertThatThrownBy(
                        () -> identity().createGroupQuery().groupMember("alice").list())
                .isInstanceOf(IdentityProviderException.class);
        assertThatThrownBy(() -> engine.getTaskService()
                        .createTaskQuery()
                        .taskCandidateUser("alice")
                        .list())
                .as("the engine wraps failures of the query parameters in a persistence exception")
                .isInstanceOf(ProcessEngineException.class)
                .hasRootCauseInstanceOf(IdentityProviderException.class);
    }
}

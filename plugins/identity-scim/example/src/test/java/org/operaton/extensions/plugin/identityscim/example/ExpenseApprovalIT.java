/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the expense process end to end: PostgreSQL for the engine, WireMock (with the mappings of
 * {@code compose/wiremock}) as the SCIM server. The people who approve and pay are whoever the
 * SCIM directory says is in the groups {@code approvers} and {@code finance}.
 */
@SpringBootTest
@Testcontainers
class ExpenseApprovalIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> scim = new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(8080)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("compose/wiremock").toAbsolutePath()), "/home/wiremock")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    @DynamicPropertySource
    static void scimServer(DynamicPropertyRegistry registry) {
        String url = "http://" + scim.getHost() + ":" + scim.getMappedPort(8080);
        registry.add("operaton.extensions.plugin.identity-scim.base-url", () -> url + "/scim/v2");
        registry.add("operaton.extensions.plugin.identity-scim.password-check.token-url", () -> url + "/oauth/token");
    }

    @Autowired
    IdentityService identityService;

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @AfterEach
    void clearAuthentication() {
        identityService.clearAuthentication();
    }

    private void authenticateAs(String userId) {
        List<String> groups = identityService.createGroupQuery().groupMember(userId).list().stream()
                .map(Group::getId)
                .toList();
        identityService.setAuthentication(userId, groups);
    }

    private List<String> candidateTasksOf(String userId, ProcessInstance instance) {
        return taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateUser(userId)
                .list()
                .stream()
                .map(Task::getName)
                .toList();
    }

    @Test
    void usersAndGroupsComeFromTheScimServer() {
        assertThat(identityService.isReadOnly()).isTrue();
        assertThat(identityService.createUserQuery().orderByUserId().asc().list())
                .extracting(User::getId)
                .containsExactly("alice", "bob", "carol", "demo");
        assertThat(identityService.createGroupQuery().groupMember("alice").list())
                .extracting(Group::getId)
                .containsExactly("approvers");
        assertThat(identityService
                        .createUserQuery()
                        .memberOfGroup("approvers")
                        .orderByUserId()
                        .asc()
                        .list())
                .extracting(User::getId)
                .containsExactly("alice", "carol");
        assertThat(identityService
                        .createUserQuery()
                        .userId("bob")
                        .singleResult()
                        .getEmail())
                .isEqualTo("bob@example.com");
    }

    @Test
    void anApprovedExpenseIsApprovedByAnApproverAndPaidByFinance() {
        authenticateAs("alice");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("expense-approval", Map.of("amount", 120));

        assertThat(candidateTasksOf("alice", instance)).containsExactly("Approve expense");
        assertThat(candidateTasksOf("carol", instance)).containsExactly("Approve expense");
        identityService.clearAuthentication();
        assertThat(candidateTasksOf("bob", instance))
                .as("bob is in finance, not in approvers")
                .isEmpty();
        assertThat(candidateTasksOf("demo", instance))
                .as("demo administers, does not approve")
                .isEmpty();

        authenticateAs("alice");
        Task approve = taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateUser("alice")
                .singleResult();
        taskService.claim(approve.getId(), "alice");
        taskService.complete(approve.getId(), Map.of("approved", true));

        identityService.clearAuthentication();
        assertThat(candidateTasksOf("alice", instance)).isEmpty();
        assertThat(candidateTasksOf("bob", instance)).containsExactly("Book payout");

        authenticateAs("bob");
        Task payout = taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateUser("bob")
                .singleResult();
        taskService.complete(payout.getId());

        identityService.clearAuthentication();
        assertThat(endActivityOf(instance)).isEqualTo("expense-paid");
    }

    @Test
    void aRejectedExpenseNeverReachesFinance() {
        authenticateAs("carol");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("expense-approval", Map.of("amount", 9999));
        Task approve = taskService
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateUser("carol")
                .singleResult();
        taskService.complete(approve.getId(), Map.of("approved", false));

        identityService.clearAuthentication();
        assertThat(candidateTasksOf("bob", instance)).isEmpty();
        assertThat(endActivityOf(instance)).isEqualTo("expense-rejected");
    }

    @Test
    void membersOfTheScimAdministratorGroupSeeEveryTask() {
        authenticateAs("alice");
        runtimeService.startProcessInstanceByKey("expense-approval", Map.of("amount", 5));
        identityService.clearAuthentication();

        authenticateAs("demo");
        assertThat(taskService.createTaskQuery().taskName("Approve expense").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void passwordsAreCheckedAtTheTokenEndpoint() {
        assertThat(identityService.checkPassword("alice", "alice")).isTrue();
        assertThat(identityService.checkPassword("alice", "wrong")).isFalse();
        assertThat(identityService.checkPassword("mallory", "mallory")).isFalse();
    }

    @Test
    void anUnavailableScimServerFailsTheLookupLoudly() throws Exception {
        String admin = "http://" + scim.getHost() + ":" + scim.getMappedPort(8080) + "/__admin/mappings";
        send("POST", admin, """
                {"priority":0,"request":{"method":"ANY","urlPathPattern":"/scim/v2/.*"},
                 "response":{"status":503,"jsonBody":{"detail":"identity directory down"}}}""");
        try {
            assertThatThrownBy(() ->
                            identityService.createUserQuery().userId("zed").singleResult())
                    .isInstanceOf(IdentityProviderException.class)
                    .hasMessageContaining("status 503")
                    .hasMessageContaining("identity directory down");
        } finally {
            send("POST", admin + "/reset", "");
        }
        assertThat(identityService.createUserQuery().userId("alice").singleResult())
                .isNotNull();
    }

    private String endActivityOf(ProcessInstance instance) {
        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .finished()
                        .count())
                .isEqualTo(1);
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(instance.getId()).list().stream()
                .filter(a -> "noneEndEvent".equals(a.getActivityType()))
                .map(HistoricActivityInstance::getActivityId)
                .findFirst()
                .orElseThrow();
    }

    private static void send(String method, String url, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Content-Type", "application/json")
                                .method(method, HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
    }
}

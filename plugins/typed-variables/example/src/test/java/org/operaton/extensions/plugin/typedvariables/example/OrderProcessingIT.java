/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.APPROVED;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.NEEDS_APPROVAL;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.ORDER;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.RECEIVED_AT;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.STATUS;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.TOTAL_CENTS;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.VALID;
import static org.operaton.extensions.plugin.typedvariables.example.OrderVariables.VALIDATION_ERRORS;
import static org.operaton.extensions.plugin.typedvariables.test.TypedVariablesAssertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.plugin.typedvariables.TypedVariables;
import org.operaton.extensions.plugin.typedvariables.VariableSet;
import org.operaton.extensions.plugin.typedvariables.example.Order.OrderLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the order process end to end against PostgreSQL: small order, large order with the manager's decision, invalid
 * order, and a process started without the order, which must end in an incident that names the missing variable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderProcessingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired
    OrderService orderService;

    @Autowired
    ProcessEngine processEngine;

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @Test
    void smallOrderIsPricedAndAcceptedWithoutApproval() {
        Order order = new Order("small-1", "alice", List.of(new OrderLine("book", 2, 1500)));

        ProcessInstance instance = orderService.submit(order);
        HistoricProcessInstance finished = awaitFinished(instance);

        assertThat(processEngine, finished)
                .hasVariable(ORDER, order)
                .hasVariable(VALID, true)
                .hasVariable(VALIDATION_ERRORS, List.of())
                .hasVariable(TOTAL_CENTS, 3000L)
                .hasVariable(NEEDS_APPROVAL, false)
                .hasNoVariable(APPROVED)
                .hasVariable(STATUS, OrderStatus.ACCEPTED)
                .hasVariable(RECEIVED_AT)
                .hasAllVariables(VariableSet.of(
                        ORDER, VALID, VALIDATION_ERRORS, TOTAL_CENTS, NEEDS_APPROVAL, STATUS, RECEIVED_AT));
    }

    @Test
    void anOrderSubmittedOverHttpIsProcessed() throws Exception {
        String json =
                "{\"id\":\"http-1\",\"customer\":\"carol\",\"lines\":[{\"sku\":\"pen\",\"quantity\":3,\"unitPriceCents\":200}]}";

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        HistoricProcessInstance finished = await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> historyService
                                .createHistoricProcessInstanceQuery()
                                .processInstanceBusinessKey("http-1")
                                .finished()
                                .singleResult(),
                        found -> found != null);
        assertThat(processEngine, finished).hasVariable(TOTAL_CENTS, 600L).hasVariable(STATUS, OrderStatus.ACCEPTED);
        assertThat(processEngine, finished)
                .hasVariableSatisfying(
                        ORDER, order -> assertThat(order.customer()).isEqualTo("carol"));
    }

    @Test
    void theOrderIsStoredAsJsonAndStillReadableByAnyJsonClient() {
        Order order = new Order("json-1", "alice", List.of(new OrderLine("pen", 1, 200)));

        ProcessInstance instance = orderService.submit(order);
        awaitFinished(instance);

        ObjectValue stored = (ObjectValue) historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceIdIn(instance.getId())
                .variableName("order")
                .disableCustomObjectDeserialization()
                .singleResult()
                .getTypedValue();
        assertThat(stored.getSerializationDataFormat()).isEqualTo("application/json");
        assertThat(stored.getValueSerialized())
                .contains("\"customer\":\"alice\"")
                .contains("\"sku\":\"pen\"")
                .contains("\"unitPriceCents\":200");
    }

    @Test
    void largeOrderWaitsForTheManagerAndIsAcceptedWhenApproved() {
        Order order = new Order("large-1", "bob", List.of(new OrderLine("laptop", 2, 90_000)));

        ProcessInstance instance = orderService.submit(order);
        Task approval = awaitApprovalTask(instance);

        // while the order waits, its variables are asserted on the running instance
        assertThat(processEngine, instance)
                .hasVariable(TOTAL_CENTS, 180_000L)
                .hasVariable(NEEDS_APPROVAL, true)
                .hasVariable(STATUS, OrderStatus.RECEIVED);

        taskService.complete(
                approval.getId(), TypedVariables.builder().set(APPROVED, true).build());
        HistoricProcessInstance finished = awaitFinished(instance);

        assertThat(processEngine, finished).hasVariable(APPROVED, true).hasVariable(STATUS, OrderStatus.ACCEPTED);
    }

    @Test
    void largeOrderIsRejectedWhenTheManagerDeclines() {
        Order order = new Order("large-2", "bob", List.of(new OrderLine("laptop", 3, 90_000)));

        ProcessInstance instance = orderService.submit(order);
        Task approval = awaitApprovalTask(instance);
        taskService.complete(
                approval.getId(), TypedVariables.builder().set(APPROVED, false).build());
        HistoricProcessInstance finished = awaitFinished(instance);

        assertThat(processEngine, finished).hasVariable(APPROVED, false).hasVariable(STATUS, OrderStatus.REJECTED);
    }

    @Test
    void invalidOrderIsRejectedWithTheReasons() {
        Order order = new Order("bad-1", " ", List.of(new OrderLine("book", 0, 1500)));

        ProcessInstance instance = orderService.submit(order);
        HistoricProcessInstance finished = awaitFinished(instance);

        assertThat(processEngine, finished)
                .hasVariable(VALID, false)
                .hasVariable(STATUS, OrderStatus.REJECTED)
                .hasNoVariable(TOTAL_CENTS)
                .hasVariableSatisfying(
                        VALIDATION_ERRORS,
                        errors -> assertThat(errors)
                                .containsExactly("customer is missing", "quantity of book must be positive"));
    }

    @Test
    void processStartedWithoutTheOrderFailsLoudlyWithAnIncidentThatNamesTheVariable() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(OrderService.PROCESS_KEY, "no-order");

        Incident incident = await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> runtimeService
                                .createIncidentQuery()
                                .processInstanceId(instance.getId())
                                .singleResult(),
                        found -> found != null);

        assertThat(incident.getActivityId()).isEqualTo("validate-order");
        assertThat(incident.getIncidentMessage())
                .contains("Required variable 'order' is missing or null in execution")
                .contains("activity validate-order");
        assertThat(processEngine, instance)
                .hasVariable(STATUS, OrderStatus.RECEIVED)
                .hasNoVariable(ORDER);
    }

    private HistoricProcessInstance awaitFinished(ProcessInstance instance) {
        return await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> historyService
                                .createHistoricProcessInstanceQuery()
                                .processInstanceId(instance.getId())
                                .finished()
                                .singleResult(),
                        found -> found != null);
    }

    private Task awaitApprovalTask(ProcessInstance instance) {
        return await().atMost(Duration.ofSeconds(30))
                .until(
                        () -> taskService
                                .createTaskQuery()
                                .processInstanceId(instance.getId())
                                .taskDefinitionKey("approve-order")
                                .singleResult(),
                        found -> found != null);
    }
}

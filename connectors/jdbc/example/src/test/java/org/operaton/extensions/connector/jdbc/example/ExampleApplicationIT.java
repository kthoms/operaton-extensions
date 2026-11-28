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
package org.operaton.extensions.connector.jdbc.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricActivityInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the order-intake process end to end: the engine uses the "operaton" database, the connector the separate
 * "business" database of the same PostgreSQL server, created by the same init script as in compose.yaml.
 */
@SpringBootTest
@Testcontainers
class ExampleApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("operaton")
            .withUsername("operaton")
            .withPassword("operaton")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("initdb", "01-business.sql")),
                    "/docker-entrypoint-initdb.d/01-business.sql");

    @DynamicPropertySource
    static void businessDatabase(DynamicPropertyRegistry registry) {
        registry.add(
                "operaton.extensions.connector.jdbc.datasources.default.url",
                () -> "jdbc:postgresql://%s:%d/business".formatted(postgres.getHost(), postgres.getMappedPort(5432)));
    }

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    HistoryService historyService;

    @BeforeEach
    void cleanOrders() throws SQLException {
        try (Connection c = business();
                Statement s = c.createStatement()) {
            s.execute("DELETE FROM sales_order");
        }
    }

    private Connection business() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://%s:%d/business".formatted(postgres.getHost(), postgres.getMappedPort(5432)),
                "operaton",
                "operaton");
    }

    private String endEvent(ProcessInstance instance) {
        return historyService
                .createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .finished()
                .list()
                .stream()
                .map(HistoricActivityInstance::getActivityId)
                .filter(id -> id.equals("orderPlaced") || id.startsWith("rejected"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void ordersOfAnActiveCustomerWithinTheLimitArePlaced() throws SQLException {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-intake", Map.of("customerId", "C-1000", "orderTotal", new BigDecimal("1234.50")));

        assertThat(instance.isEnded()).isTrue();
        assertThat(endEvent(instance)).isEqualTo("orderPlaced");
        Object orderId = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("orderId")
                .singleResult()
                .getValue();
        try (Connection c = business();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT customer_id, total FROM sales_order WHERE id = " + orderId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("customer_id")).isEqualTo("C-1000");
            assertThat(rs.getBigDecimal("total")).isEqualByComparingTo("1234.50");
        }
    }

    @Test
    void unknownCustomersAreRejectedWithoutWritingAnOrder() throws SQLException {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-intake", Map.of("customerId", "C-9999", "orderTotal", new BigDecimal("10.00")));

        assertThat(endEvent(instance)).isEqualTo("rejectedUnknown");
        assertThat(orderCount()).isZero();
    }

    @Test
    void inactiveCustomersAreRejected() throws SQLException {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-intake", Map.of("customerId", "C-2000", "orderTotal", new BigDecimal("10.00")));

        assertThat(endEvent(instance)).isEqualTo("rejectedUnknown");
        assertThat(orderCount()).isZero();
    }

    @Test
    void ordersAboveTheCreditLimitAreRejected() throws SQLException {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-intake", Map.of("customerId", "C-3000", "orderTotal", new BigDecimal("100.01")));

        assertThat(endEvent(instance)).isEqualTo("rejectedLimit");
        assertThat(orderCount()).isZero();
    }

    @Test
    void anInjectionAttemptInTheCustomerIdIsJustAnUnknownCustomer() throws SQLException {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-intake", Map.of("customerId", "C-1000' OR '1'='1", "orderTotal", new BigDecimal("1.00")));

        assertThat(endEvent(instance)).isEqualTo("rejectedUnknown");
        assertThat(orderCount()).isZero();
    }

    private long orderCount() throws SQLException {
        try (Connection c = business();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales_order")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

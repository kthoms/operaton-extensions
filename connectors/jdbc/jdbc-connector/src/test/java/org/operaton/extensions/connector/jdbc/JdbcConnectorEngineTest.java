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
package org.operaton.extensions.connector.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.connect.Connectors;
import org.operaton.connect.spi.Connector;
import org.operaton.spin.json.SpinJsonNode;

/** The connectors and delegates run from BPMN in an embedded engine against an H2 business database. */
class JdbcConnectorEngineTest {

    static final TestDatabase BUSINESS = new TestDatabase();
    static JdbcConnectorPlugin plugin;
    static JdbcOperations directly;
    static JdbcDataSourceRegistry directRegistry;

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(configuration -> {
                BUSINESS.createSchema();
                directRegistry = new JdbcDataSourceRegistry(
                        Map.of(JdbcDataSourceRegistry.DEFAULT_NAME, BUSINESS.properties), Map.of());
                directly = new JdbcOperations(directRegistry);
                plugin = EngineTestSupport.install(
                        configuration, Map.of(JdbcDataSourceRegistry.DEFAULT_NAME, BUSINESS.properties));
            })
            .build();

    @AfterAll
    static void closePlugin() {
        plugin.close();
        directRegistry.close();
    }

    private ProcessInstance start(String key, Map<String, Object> variables) {
        return engine.getRuntimeService().startProcessInstanceByKey(key, variables);
    }

    private Object variable(ProcessInstance instance, String name) {
        return engine.getRuntimeService().getVariable(instance.getId(), name);
    }

    @Test
    void connectorsAreRegisteredThroughTheServiceLoader() {
        Connector<?> query = Connectors.getConnector("jdbc-query");
        Connector<?> update = Connectors.getConnector("jdbc-update");

        assertThat(query.getId()).isEqualTo("jdbc-query");
        assertThat(update.getId()).isEqualTo("jdbc-update");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void queryReturnsRowsAsListOfMaps() {
        ProcessInstance instance = start("query-list", Map.of("active", true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> customers = (List<Map<String, Object>>) variable(instance, "customers");
        assertThat(customers).hasSize(2);
        assertThat(customers.get(0)).containsEntry("ID", "C-1").containsEntry("NAME", "Alice");
        assertThat(customers.get(0).get("CREDIT_LIMIT")).isEqualTo(new BigDecimal("1500.50"));
        assertThat(variable(instance, "count")).isEqualTo(2);
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void singleRowIsAMapAndColumnCaseApplies() {
        ProcessInstance instance = start("query-single", Map.of("customerId", "C-2", "allowEmpty", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) variable(instance, "customer");
        assertThat(customer).containsEntry("id", "C-2").containsEntry("name", "Bob");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void singleRowOfNothingFailsUnlessAllowed() {
        assertThatThrownBy(() -> start("query-single", Map.of("customerId", "nobody", "allowEmpty", false)))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("no rows");

        ProcessInstance instance = start("query-single", Map.of("customerId", "nobody", "allowEmpty", true));
        assertThat(variable(instance, "customer")).isNull();
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void singleValueIsTheScalar() {
        ProcessInstance instance = start("query-value", Map.of("customerId", "C-3"));

        assertThat(variable(instance, "limit")).isEqualTo(new BigDecimal("99999999999.99"));
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void parametersCanBeGivenAsAMap() {
        ProcessInstance instance = start("query-map-params", Map.of("customerId", "C-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) variable(instance, "customer");
        assertThat(customer).containsEntry("NAME", "Alice");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void resultAsJsonIsASpinNodeWithExactDecimals() {
        ProcessInstance instance = start("query-json", Map.of());

        SpinJsonNode customers = (SpinJsonNode) variable(instance, "customers");
        assertThat(customers.isArray()).isTrue();
        assertThat(customers.elements()).hasSize(3);
        assertThat(customers.elements().get(2).prop("CREDIT_LIMIT").stringValue())
                .isEqualTo("99999999999.99");
        assertThat(customers.elements().get(0).prop("NAME").stringValue()).isEqualTo("Alice");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void moreRowsThanMaxRowsFailTheTask() {
        assertThatThrownBy(() -> start("query-max-rows", Map.of()))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("more than 2 rows");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void emptyResultCanBeModelledAsBpmnError() {
        ProcessInstance instance = start("query-not-found", Map.of("customerId", "nobody"));

        assertThat(instance.isEnded()).isTrue();
        assertThat(engine.getHistoryService()
                        .createHistoricActivityInstanceQuery()
                        .processInstanceId(instance.getId())
                        .activityId("query-not-found-endNotFound")
                        .count())
                .isEqualTo(1);
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void updateInsertsAndReturnsTheGeneratedKey() {
        ProcessInstance instance = start(
                "update-insert", Map.of("customerId", "C-1", "total", new BigDecimal("42.10"), "placedAt", new Date()));

        assertThat(variable(instance, "updateCount")).isEqualTo(1);
        Object orderId = variable(instance, "orderId");
        assertThat(orderId).isInstanceOf(Long.class);
        JdbcOperations.QueryResult stored = directly.query(
                new JdbcOperations.Query(null, "SELECT total FROM orders WHERE id = :id", Map.of("id", orderId)));
        assertThat(stored.rows().get(0).get("TOTAL")).isEqualTo(new BigDecimal("42.10"));
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-connector.bpmn")
    void sqlErrorFailsTheTaskWithTheCauseChain() {
        assertThatThrownBy(() -> start("query-bad-sql", Map.of()))
                .isInstanceOf(JdbcConnectorException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    // ---- delegates ----

    @Test
    @Deployment(resources = "bpmn/jdbc-delegate.bpmn")
    void queryDelegateStoresTheResultInAVariable() {
        ProcessInstance instance = start("delegate-query", Map.of("customerId", "C-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) variable(instance, "customer");
        assertThat(customer).containsEntry("NAME", "Alice");
        assertThat(variable(instance, "rows")).isEqualTo(1);
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-delegate.bpmn")
    void updateDelegateBindsProcessVariablesAndReturnsKey() {
        ProcessInstance instance =
                start("delegate-update", Map.of("customerId", "C-2", "total", new BigDecimal("7.00")));

        assertThat(variable(instance, "updated")).isEqualTo(1);
        assertThat(variable(instance, "orderId")).isInstanceOf(Long.class);
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-delegate.bpmn")
    void delegateRejectsSqlFromAnExpression() {
        assertThatThrownBy(() -> start("delegate-dynamic-sql", Map.of("sqlText", "SELECT 1")))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("must be literal text");
    }

    @Test
    @Deployment(resources = "bpmn/jdbc-delegate.bpmn")
    void delegateFailsForAParameterWithoutValue() {
        assertThatThrownBy(() -> start("delegate-missing-param", Map.of()))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining(":nothing");
    }
}

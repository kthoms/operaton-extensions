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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcOperationsH2Test {

    private JdbcDataSourceRegistry registry;
    private JdbcOperations jdbc;

    @BeforeEach
    void setUp() {
        registry = new TestDatabase().registry();
        jdbc = new JdbcOperations(registry);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private JdbcOperations.QueryResult select(String sql, Map<String, Object> params) {
        return jdbc.query(new JdbcOperations.Query(null, sql, params));
    }

    @Test
    void mapsSqlTypesToExactJavaTypes() {
        Map<String, Object> row = select("SELECT * FROM customer WHERE id = :id", Map.of("id", "C-1"))
                .rows()
                .get(0);

        assertThat(row.get("ID")).isEqualTo("C-1");
        assertThat(row.get("CREDIT_LIMIT")).isEqualTo(new BigDecimal("1500.50")).isInstanceOf(BigDecimal.class);
        assertThat(row.get("ACTIVE")).isEqualTo(true);
        assertThat(row.get("SINCE")).isEqualTo(LocalDate.of(2020, 2, 29));
        assertThat(row.get("CREATED_AT")).isEqualTo(LocalDateTime.of(2024, 5, 6, 7, 8, 9));
        assertThat(row.get("NOTES")).isNull();
    }

    @Test
    void decimalsKeepEveryDigit() {
        Object limit = select("SELECT credit_limit FROM customer WHERE id = :id", Map.of("id", "C-3"))
                .rows()
                .get(0)
                .get("CREDIT_LIMIT");

        assertThat(limit).isEqualTo(new BigDecimal("99999999999.99"));
    }

    @Test
    void columnCaseOptionNormalisesLabels() {
        JdbcOperations.QueryResult result = jdbc.query(new JdbcOperations.Query(
                null,
                "SELECT id, credit_limit AS \"creditLimit\" FROM customer WHERE id = :id",
                Map.of("id", "C-1"),
                Map.of(),
                null,
                null,
                false,
                ColumnCase.LOWER));

        assertThat(result.columns()).containsExactly("id", "creditlimit");
    }

    @Test
    void duplicateColumnLabelsFail() {
        assertThatThrownBy(() -> select("SELECT id, id FROM customer", Map.of()))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("Duplicate column");
    }

    @Test
    void bindsTypedValues() {
        jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO customer (id, name, credit_limit, active, since, created_at) VALUES"
                        + " (:id, :name, :limit, :active, :since, :created)",
                Map.of(
                        "id",
                        "C-9",
                        "name",
                        "Dora",
                        "limit",
                        new BigDecimal("12.34"),
                        "active",
                        true,
                        "since",
                        LocalDate.of(2000, 1, 2),
                        "created",
                        LocalDateTime.of(2001, 2, 3, 4, 5, 6))));

        Map<String, Object> row = select("SELECT * FROM customer WHERE id = 'C-9'", Map.of())
                .rows()
                .get(0);
        assertThat(row.get("CREDIT_LIMIT")).isEqualTo(new BigDecimal("12.34"));
        assertThat(row.get("SINCE")).isEqualTo(LocalDate.of(2000, 1, 2));
        assertThat(row.get("CREATED_AT")).isEqualTo(LocalDateTime.of(2001, 2, 3, 4, 5, 6));
    }

    @Test
    void typeHintsConvertTextAndTypeNulls() {
        Map<String, Object> params = new HashMap<>();
        params.put("id", "C-8");
        params.put("since", "2010-10-10");
        params.put("limit", "5.5");
        params.put("notes", null);

        jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO customer (id, name, credit_limit, active, since, notes) VALUES"
                        + " (:id, 'Eve', :limit, TRUE, :since, :notes)",
                params,
                Map.of("since", SqlTypeHint.DATE, "limit", SqlTypeHint.DECIMAL, "notes", SqlTypeHint.STRING),
                null,
                false,
                List.of(),
                ColumnCase.AS_IS));

        Map<String, Object> row = select("SELECT * FROM customer WHERE id = 'C-8'", Map.of())
                .rows()
                .get(0);
        assertThat(row.get("SINCE")).isEqualTo(LocalDate.of(2010, 10, 10));
        assertThat(row.get("CREDIT_LIMIT")).isEqualTo(new BigDecimal("5.50"));
        assertThat(row.get("NOTES")).isNull();
    }

    @Test
    void hintConversionFailureNamesTheParameter() {
        assertThatThrownBy(() -> jdbc.update(new JdbcOperations.Update(
                        null,
                        "UPDATE customer SET since = :since",
                        Map.of("since", "not-a-date"),
                        Map.of("since", SqlTypeHint.DATE),
                        null,
                        false,
                        List.of(),
                        ColumnCase.AS_IS)))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining(":since");
    }

    @Test
    void unsupportedParameterTypeFails() {
        assertThatThrownBy(() -> select("SELECT * FROM customer WHERE id = :id", Map.of("id", new Object())))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("unsupported type");
    }

    @Test
    void inListExpandsToOneBoundValuePerElement() {
        List<Map<String, Object>> rows = select(
                        "SELECT id FROM customer WHERE id IN (:ids) ORDER BY id", Map.of("ids", List.of("C-1", "C-3")))
                .rows();

        assertThat(rows).extracting(r -> r.get("ID")).containsExactly("C-1", "C-3");
    }

    @Test
    void updateReturnsCountAndGeneratedKeys() {
        JdbcOperations.UpdateResult result = jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO orders (customer_id, total) VALUES (:c, :t)",
                Map.of("c", "C-1", "t", new BigDecimal("10.00")),
                Map.of(),
                null,
                true,
                List.of(),
                ColumnCase.AS_IS));

        assertThat(result.updateCount()).isEqualTo(1);
        assertThat(result.generatedKeys()).hasSize(1);
        assertThat(result.generatedKeys().get(0).values().iterator().next()).isEqualTo(1L);
    }

    @Test
    void generatedKeyColumnsSelectTheReturnedColumns() {
        JdbcOperations.UpdateResult result = jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO orders (customer_id, total) VALUES (:c, :t)",
                Map.of("c", "C-1", "t", new BigDecimal("10.00")),
                Map.of(),
                null,
                false,
                List.of("ID"),
                ColumnCase.LOWER));

        assertThat(result.generatedKeys().get(0)).containsOnlyKeys("id");
    }

    @Test
    void updateWithoutKeysReturnsNoKeys() {
        JdbcOperations.UpdateResult result = jdbc.update(new JdbcOperations.Update(
                null, "UPDATE customer SET active = FALSE WHERE id <> :id", Map.of("id", "C-1")));

        assertThat(result.updateCount()).isEqualTo(2);
        assertThat(result.generatedKeys()).isEmpty();
    }

    @Test
    void moreRowsThanMaxRowsFailUnlessTruncating() {
        JdbcOperations.Query strict = new JdbcOperations.Query(
                null, "SELECT id FROM customer ORDER BY id", Map.of(), Map.of(), null, 2, false, ColumnCase.AS_IS);
        JdbcOperations.Query truncating = new JdbcOperations.Query(
                null, "SELECT id FROM customer ORDER BY id", Map.of(), Map.of(), null, 2, true, ColumnCase.AS_IS);

        assertThatThrownBy(() -> jdbc.query(strict))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("more than 2 rows");
        JdbcOperations.QueryResult result = jdbc.query(truncating);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactlyMaxRowsIsNotTruncated() {
        JdbcOperations.QueryResult result = jdbc.query(new JdbcOperations.Query(
                null, "SELECT id FROM customer", Map.of(), Map.of(), null, 3, false, ColumnCase.AS_IS));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void unknownDataSourceListsTheConfiguredOnes() {
        assertThatThrownBy(() -> jdbc.query(new JdbcOperations.Query("nope", "SELECT 1", Map.of())))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("Unknown data source 'nope'")
                .hasMessageContaining("default");
    }

    @Test
    void sqlErrorsCarrySqlStateAndAreChained() {
        assertThatThrownBy(() -> select("SELECT * FROM no_such_table", Map.of()))
                .isInstanceOfSatisfying(JdbcConnectorException.class, e -> {
                    assertThat(e.getSqlState()).isNotBlank();
                    assertThat(e).hasCauseInstanceOf(java.sql.SQLException.class);
                });
    }

    @Test
    void ownConnectionAutoCommits() {
        jdbc.update(
                new JdbcOperations.Update(null, "INSERT INTO orders (customer_id, total) VALUES ('C-1', 1)", Map.of()));

        // a second, independent pooled connection sees it
        assertThat(select("SELECT COUNT(*) AS n FROM orders", Map.of())
                        .rows()
                        .get(0)
                        .get("N"))
                .isEqualTo(1L);
    }

    // ---- SQL injection: impossible by construction ----

    @Test
    void injectionInAParameterValueIsJustData() {
        String attack = "x' OR '1'='1";

        assertThat(select("SELECT * FROM customer WHERE name = :name", Map.of("name", attack))
                        .rows())
                .isEmpty();
    }

    @Test
    void stackedStatementInAValueDoesNotRun() {
        String attack = "C-1'; DROP TABLE customer; --";

        select("SELECT * FROM customer WHERE id = :id", Map.of("id", attack));

        assertThat(select("SELECT COUNT(*) AS n FROM customer", Map.of())
                        .rows()
                        .get(0)
                        .get("N"))
                .isEqualTo(3L);
    }

    @Test
    void injectionValueIsStoredVerbatimByInsert() {
        String attack = "Robert'); DROP TABLE customer;--";

        jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO customer (id, name, credit_limit, active) VALUES ('C-7', :name, 1, TRUE)",
                Map.of("name", attack)));

        assertThat(select("SELECT name FROM customer WHERE id = 'C-7'", Map.of())
                        .rows()
                        .get(0)
                        .get("NAME"))
                .isEqualTo(attack);
        assertThat(select("SELECT COUNT(*) AS n FROM customer", Map.of())
                        .rows()
                        .get(0)
                        .get("N"))
                .isEqualTo(4L);
    }

    @Test
    void aColonInsideAValueIsNotAParameter() {
        assertThat(select("SELECT :v AS v FROM customer WHERE id = 'C-1'", Map.of("v", "a :b c"))
                        .rows()
                        .get(0)
                        .get("V"))
                .isEqualTo("a :b c");
    }

    @Test
    void anUnboundParameterIsRejectedBeforeAnythingRuns() {
        assertThatThrownBy(() -> jdbc.update(new JdbcOperations.Update(
                        null, "DELETE FROM customer WHERE id = :id OR name = :name", Map.of("id", "C-1"))))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining(":name");
        assertThat(select("SELECT COUNT(*) AS n FROM customer", Map.of())
                        .rows()
                        .get(0)
                        .get("N"))
                .isEqualTo(3L);
    }

    @Test
    void stackedStatementsInTheSqlTextAreRejected() {
        assertThatThrownBy(() -> select("SELECT 1; DROP TABLE customer", Map.of()))
                .isInstanceOf(JdbcConnectorException.class);
        assertThat(select("SELECT COUNT(*) AS n FROM customer", Map.of())
                        .rows()
                        .get(0)
                        .get("N"))
                .isEqualTo(3L);
    }

    @Test
    void closedRegistryRefusesCalls() {
        registry.close();

        assertThatThrownBy(() -> select("SELECT 1", Map.of()))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("closed");
    }
}

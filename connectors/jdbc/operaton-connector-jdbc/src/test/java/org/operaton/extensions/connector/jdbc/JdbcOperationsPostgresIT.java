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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The connector core against a real PostgreSQL: types, timeouts, generated keys and injection attempts. */
@Testcontainers
class JdbcOperationsPostgresIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static JdbcDataSourceRegistry registry;
    static JdbcOperations jdbc;

    @BeforeAll
    static void connect() {
        JdbcDataSourceProperties properties = new JdbcDataSourceProperties();
        properties.setUrl(postgres.getJdbcUrl());
        properties.setUsername(postgres.getUsername());
        properties.setPassword(postgres.getPassword());
        registry = new JdbcDataSourceRegistry(Map.of("default", properties), Map.of());
        jdbc = new JdbcOperations(registry);
    }

    @AfterAll
    static void close() {
        registry.close();
    }

    @BeforeEach
    void schema() {
        update("DROP TABLE IF EXISTS item", Map.of());
        update(
                "CREATE TABLE item (id BIGSERIAL PRIMARY KEY, ref UUID, price NUMERIC(30,10), born DATE,"
                        + " seen TIMESTAMP, seen_tz TIMESTAMPTZ, doc JSONB, tags TEXT[], blob BYTEA, small SMALLINT,"
                        + " flag BOOLEAN, note TEXT)",
                Map.of());
    }

    private JdbcOperations.UpdateResult update(String sql, Map<String, Object> params) {
        return jdbc.update(new JdbcOperations.Update(null, sql, params));
    }

    private List<Map<String, Object>> select(String sql, Map<String, Object> params) {
        return jdbc.query(new JdbcOperations.Query(null, sql, params)).rows();
    }

    @Test
    void roundTripsEveryMappedType() {
        UUID ref = UUID.randomUUID();
        BigDecimal price = new BigDecimal("12345678901234567890.1234567891");
        OffsetDateTime seenTz = OffsetDateTime.of(2024, 3, 4, 5, 6, 7, 0, ZoneOffset.ofHours(2));
        Map<String, Object> params = new HashMap<>();
        params.put("ref", ref);
        params.put("price", price);
        params.put("born", LocalDate.of(1999, 12, 31));
        params.put("seen", LocalDateTime.of(2024, 3, 4, 5, 6, 7));
        params.put("seenTz", seenTz);
        params.put("doc", "{\"a\": [1, 2]}");
        params.put("blob", new byte[] {1, 2, 3});
        params.put("small", (short) 7);
        params.put("flag", true);
        params.put("note", null);

        JdbcOperations.UpdateResult inserted = jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO item (ref, price, born, seen, seen_tz, doc, tags, blob, small, flag, note) VALUES"
                        + " (:ref, :price, :born, :seen, :seenTz, :doc, ARRAY['x','y'], :blob, :small, :flag, :note)",
                params,
                Map.of("doc", SqlTypeHint.JSON, "note", SqlTypeHint.STRING),
                null,
                true,
                List.of(),
                ColumnCase.AS_IS));
        assertThat(inserted.updateCount()).isEqualTo(1);
        assertThat(inserted.generatedKeys().get(0)).containsEntry("id", 1L);

        Map<String, Object> row = select("SELECT * FROM item", Map.of()).get(0);
        assertThat(row.get("ref")).isEqualTo(ref);
        assertThat(row.get("price")).isEqualTo(price);
        assertThat(row.get("born")).isEqualTo(LocalDate.of(1999, 12, 31));
        assertThat(row.get("seen")).isEqualTo(LocalDateTime.of(2024, 3, 4, 5, 6, 7));
        assertThat(((OffsetDateTime) row.get("seen_tz")).toInstant()).isEqualTo(seenTz.toInstant());
        assertThat(((String) row.get("doc")).replace(" ", "")).contains("\"a\":[1,2]");
        assertThat(row.get("tags")).isEqualTo(List.of("x", "y"));
        assertThat((byte[]) row.get("blob")).containsExactly(1, 2, 3);
        assertThat(row.get("small")).isEqualTo(7);
        assertThat(row.get("flag")).isEqualTo(true);
        assertThat(row.get("note")).isNull();
    }

    @Test
    void textHintsBindUuidAndDates() {
        UUID ref = UUID.randomUUID();

        jdbc.update(new JdbcOperations.Update(
                null,
                "INSERT INTO item (ref, born) VALUES (:ref, :born)",
                Map.of("ref", ref.toString(), "born", "2001-02-03"),
                Map.of("ref", SqlTypeHint.UUID, "born", SqlTypeHint.DATE),
                null,
                false,
                List.of(),
                ColumnCase.AS_IS));

        Map<String, Object> row = select("SELECT ref, born FROM item", Map.of()).get(0);
        assertThat(row.get("ref")).isEqualTo(ref);
        assertThat(row.get("born")).isEqualTo(LocalDate.of(2001, 2, 3));
    }

    @Test
    void queryTimeoutCancelsTheStatement() {
        assertThatThrownBy(() -> jdbc.query(new JdbcOperations.Query(
                        null, "SELECT pg_sleep(5)", Map.of(), Map.of(), 1, null, false, ColumnCase.AS_IS)))
                .isInstanceOfSatisfying(
                        JdbcConnectorException.class,
                        e -> assertThat(e.getSqlState()).isEqualTo("57014"));
    }

    @Test
    void injectionAttemptsAreStoredAsData() {
        String attack = "x'); DROP TABLE item; --";

        update("INSERT INTO item (note) VALUES (:n)", Map.of("n", attack));

        assertThat(select("SELECT note FROM item WHERE note = :n", Map.of("n", attack)))
                .hasSize(1);
        assertThat(select("SELECT COUNT(*) AS n FROM item", Map.of()).get(0).get("n"))
                .isEqualTo(1L);
    }

    @Test
    void castSyntaxAndInListWorkWithNamedParameters() {
        update("INSERT INTO item (small, note) VALUES (1, 'a'), (2, 'b'), (3, 'c')", Map.of());

        List<Map<String, Object>> rows = select(
                "SELECT note, small::text AS s FROM item WHERE small IN (:ids) ORDER BY small",
                Map.of("ids", List.of(1, 3)));

        assertThat(rows).extracting(r -> r.get("note")).containsExactly("a", "c");
        assertThat(rows.get(0).get("s")).isEqualTo("1");
    }

    @Test
    void sqlErrorsKeepTheSqlState() {
        assertThatThrownBy(() -> select("SELECT * FROM missing_table", Map.of()))
                .isInstanceOfSatisfying(
                        JdbcConnectorException.class,
                        e -> assertThat(e.getSqlState()).isEqualTo("42P01"));
    }
}

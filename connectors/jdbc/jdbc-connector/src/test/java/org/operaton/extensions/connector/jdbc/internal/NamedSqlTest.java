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
package org.operaton.extensions.connector.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;

class NamedSqlTest {

    @Test
    void replacesNamedParametersByPlaceholdersInOrder() {
        NamedSql.Bound bound = NamedSql.parse("SELECT * FROM t WHERE a = :a AND b = :b AND c = :a")
                .bind(Map.of("a", 1, "b", "x"));

        assertThat(bound.sql()).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ? AND c = ?");
        assertThat(bound.values()).containsExactly(1, "x", 1);
        assertThat(bound.names()).containsExactly("a", "b", "a");
    }

    @Test
    void valuesNeverAppearInTheSqlText() {
        String attack = "'; DROP TABLE customer; --";

        NamedSql.Bound bound =
                NamedSql.parse("SELECT * FROM customer WHERE name = :name").bind(Map.of("name", attack));

        assertThat(bound.sql()).doesNotContain("DROP").isEqualTo("SELECT * FROM customer WHERE name = ?");
        assertThat(bound.values()).containsExactly(attack);
    }

    @Test
    void ignoresColonsInLiteralsIdentifiersCommentsCastsAndDollarQuotes() {
        String sql = "SELECT ':lit', \":ident\", x::int, `:tick`, $$ :dollar $$, $tag$ :tagged $tag$ -- :line\n"
                + "/* :block */ FROM t WHERE a = :real";

        NamedSql parsed = NamedSql.parse(sql);

        assertThat(parsed.parameterNames()).containsExactly("real");
        assertThat(parsed.bind(Map.of("real", 1)).sql()).startsWith("SELECT ':lit', \":ident\", x::int");
    }

    @Test
    void escapedQuoteDoesNotEndTheLiteral() {
        NamedSql parsed = NamedSql.parse("SELECT 'it''s :not a param' FROM t WHERE a = :p");

        assertThat(parsed.parameterNames()).containsExactly("p");
    }

    @Test
    void rejectsUnboundParameters() {
        NamedSql parsed = NamedSql.parse("SELECT * FROM t WHERE a = :a AND b = :b");

        assertThatThrownBy(() -> parsed.bind(Map.of("a", 1)))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining(":b");
    }

    @Test
    void nullIsABoundValueButAMissingKeyIsNot() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("a", null);

        assertThat(NamedSql.parse("SELECT :a").bind(withNull).values()).containsExactly((Object) null);
    }

    @Test
    void expandsCollectionsForInLists() {
        NamedSql.Bound bound = NamedSql.parse("SELECT * FROM t WHERE id IN (:ids) AND x = :x")
                .bind(Map.of("ids", List.of(1, 2, 3), "x", 9));

        assertThat(bound.sql()).isEqualTo("SELECT * FROM t WHERE id IN (?, ?, ?) AND x = ?");
        assertThat(bound.values()).containsExactly(1, 2, 3, 9);
        assertThat(bound.names()).containsExactly("ids", "ids", "ids", "x");
    }

    @Test
    void rejectsEmptyLists() {
        NamedSql parsed = NamedSql.parse("SELECT * FROM t WHERE id IN (:ids)");

        assertThatThrownBy(() -> parsed.bind(Map.of("ids", List.of())))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsPositionalMarkers() {
        assertThatThrownBy(() -> NamedSql.parse("SELECT * FROM t WHERE a = ?"))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("Positional");
    }

    @Test
    void rejectsSecondStatements() {
        assertThatThrownBy(() -> NamedSql.parse("SELECT 1; DROP TABLE t"))
                .isInstanceOf(JdbcConnectorException.class)
                .hasMessageContaining("More than one");
        assertThatThrownBy(() -> NamedSql.parse("SELECT 1; SELECT :a")).isInstanceOf(JdbcConnectorException.class);
    }

    @Test
    void acceptsTrailingSemicolonAndTrailingComment() {
        assertThat(NamedSql.parse("SELECT 1 ;  ").bind(Map.of()).sql()).isEqualTo("SELECT 1");
        assertThat(NamedSql.parse("SELECT 1; -- done").bind(Map.of()).sql()).startsWith("SELECT 1");
    }

    @Test
    void rejectsUnterminatedLiteralsAndComments() {
        assertThatThrownBy(() -> NamedSql.parse("SELECT 'abc")).isInstanceOf(JdbcConnectorException.class);
        assertThatThrownBy(() -> NamedSql.parse("SELECT \"abc")).isInstanceOf(JdbcConnectorException.class);
        assertThatThrownBy(() -> NamedSql.parse("SELECT /* abc")).isInstanceOf(JdbcConnectorException.class);
        assertThatThrownBy(() -> NamedSql.parse("SELECT $$ abc")).isInstanceOf(JdbcConnectorException.class);
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> NamedSql.parse("  ")).isInstanceOf(JdbcConnectorException.class);
        assertThatThrownBy(() -> NamedSql.parse(null)).isInstanceOf(JdbcConnectorException.class);
    }
}

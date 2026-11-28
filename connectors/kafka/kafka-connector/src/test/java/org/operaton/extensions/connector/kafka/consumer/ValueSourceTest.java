/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.kafka.producer.MessageHeader;

class ValueSourceTest {

    private static final InboundRecord RECORD = new InboundRecord(
            "orders",
            2,
            41L,
            1000L,
            "k1",
            "{\"orderId\":\"A-1\",\"amount\":12.5,\"count\":3,\"paid\":true,\"customer\":{\"name\":\"Ada\"},"
                    + "\"items\":[{\"sku\":\"x\"},{\"sku\":\"y\"}],\"a.b\":\"dotted\",\"none\":null}",
            List.of(MessageHeader.text("h", "old"), MessageHeader.text("h", "new")));

    private static Object extract(String expression) {
        return ValueSource.parse(expression).extract(RECORD);
    }

    @Test
    void metadataSources() {
        assertThat(extract("key")).isEqualTo("k1");
        assertThat(extract("topic")).isEqualTo("orders");
        assertThat(extract("partition")).isEqualTo(2L);
        assertThat(extract("offset")).isEqualTo(41L);
        assertThat(extract("timestamp")).isEqualTo(1000L);
        assertThat(extract("header:h")).isEqualTo("new");
        assertThat(extract("const:fixed")).isEqualTo("fixed");
        assertThat((String) extract("value")).startsWith("{\"orderId\"");
    }

    @Test
    void jsonPaths() {
        assertThat(extract("$.orderId")).isEqualTo("A-1");
        assertThat(extract("$.amount")).isEqualTo(12.5);
        assertThat(extract("$.count")).isEqualTo(3L);
        assertThat(extract("$.paid")).isEqualTo(true);
        assertThat(extract("$.customer.name")).isEqualTo("Ada");
        assertThat(extract("$.customer")).isEqualTo("{\"name\":\"Ada\"}");
        assertThat(extract("$.items[1].sku")).isEqualTo("y");
        assertThat(extract("$['a.b']")).isEqualTo("dotted");
    }

    @Test
    void absentValuesFailUnlessOptional() {
        assertThat(extract("$.missing?")).isNull();
        assertThat(extract("$.none?")).isNull();
        assertThat(extract("header:nope?")).isNull();
        assertThatThrownBy(() -> extract("$.missing"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("orders-2@41");
        assertThatThrownBy(() -> extract("$.items[5].sku")).isInstanceOf(MappingException.class);
        assertThatThrownBy(() -> extract("header:nope")).isInstanceOf(MappingException.class);
    }

    @Test
    void nonJsonValueFailsJsonPaths() {
        InboundRecord text = new InboundRecord("t", 0, 0, 0, null, "plain text", List.of());

        assertThatThrownBy(() -> ValueSource.parse("$.a").extract(text))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("not valid JSON");
        assertThat(ValueSource.parse("value").extract(text)).isEqualTo("plain text");
    }

    @Test
    void malformedExpressionsAreRejected() {
        for (String bad : List.of("", "foo", "header:", "$.", "$[x]", "$[1", "$a")) {
            assertThatThrownBy(() -> ValueSource.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

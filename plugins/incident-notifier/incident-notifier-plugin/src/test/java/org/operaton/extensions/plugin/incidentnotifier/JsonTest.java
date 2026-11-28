/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesNestedStructuresWithEscaping() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", "q\"uote\\ \n\t\u0001");
        map.put("n", 3);
        map.put("b", true);
        map.put("z", null);
        map.put("l", List.of("x", Map.of("k", "v")));

        assertThat(Json.write(map))
                .isEqualTo(
                        "{\"a\":\"q\\\"uote\\\\ \\n\\t\\u0001\",\"n\":3,\"b\":true,\"z\":null,\"l\":[\"x\",{\"k\":\"v\"}]}");
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}

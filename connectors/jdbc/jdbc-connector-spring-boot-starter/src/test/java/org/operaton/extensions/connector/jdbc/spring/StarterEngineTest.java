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
package org.operaton.extensions.connector.jdbc.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

/** The Operaton Spring Boot engine picks up the plugin and runs the connector against the second database. */
@SpringBootTest(
        classes = StarterEngineTest.App.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:starter-engine;DB_CLOSE_DELAY=-1",
            "operaton.bpm.database.schema-update=true",
            "operaton.extensions.connector.jdbc.datasources.business.url=jdbc:h2:mem:starter-business;DB_CLOSE_DELAY=-1",
            "operaton.extensions.connector.jdbc.datasources.business.username=sa"
        })
class StarterEngineTest {

    @SpringBootApplication
    static class App {}

    @Autowired
    RuntimeService runtimeService;

    @Test
    void connectorRunsInTheSpringBootEngine() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:h2:mem:starter-business;DB_CLOSE_DELAY=-1", "sa", "");
                var s = c.createStatement()) {
            s.execute("CREATE TABLE greeting (id INT PRIMARY KEY, text VARCHAR(50))");
            s.execute("INSERT INTO greeting VALUES (1, 'hello')");
        }

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("starter-lookup", Map.of("id", 1));

        assertThat(runtimeService.getVariable(instance.getId(), "greeting")).isEqualTo("hello");
    }
}

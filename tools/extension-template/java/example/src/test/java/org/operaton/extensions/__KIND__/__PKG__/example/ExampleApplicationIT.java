/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.__KIND__.__PKG__.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the example's process end to end against PostgreSQL. Replace with the real scenario. */
@SpringBootTest
@Testcontainers
class ExampleApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    HistoryService historyService;

    @Test
    void processRunsToTheEnd() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("example");

        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(instance.getId())
                        .finished()
                        .count())
                .isEqualTo(1);
    }
}

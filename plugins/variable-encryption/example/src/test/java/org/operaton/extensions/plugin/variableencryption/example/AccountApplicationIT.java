/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the account-opening process end to end against PostgreSQL and reads the raw rows through
 * JDBC: the personal data is ciphertext there, plain through the engine API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountApplicationIT {

    static final String IBAN = "DE89370400440532013000";
    static final ApplicationRequest APPLICATION = new ApplicationRequest(
            "Ada Lovelace",
            "12 Analytical Street",
            "10115 Berlin",
            "TAXID-12-345-678",
            IBAN,
            LocalDate.of(1990, 12, 10),
            "C01X00T47",
            "Giro account");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    int port;

    @Autowired
    AccountApplicationService service;

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @Autowired
    StoredRows storedRows;

    @Autowired
    JdbcTemplate jdbc;

    /** Every text column and byte array of the variable rows of one instance, as one string. */
    private String everythingStoredOf(String processInstanceId) {
        StringBuilder all = new StringBuilder();
        for (StoredRows.Row row : storedRows.of(processInstanceId)) {
            all.append(row.variable())
                    .append('|')
                    .append(row.text())
                    .append('|')
                    .append(row.longValue())
                    .append('|');
            if (row.content() != null) {
                all.append(new String(row.content(), StandardCharsets.ISO_8859_1));
            }
            all.append('\n');
        }
        return all.toString();
    }

    @Test
    void personalDataIsCiphertextInTheDatabaseAndPlainThroughTheEngine() {
        String id = service.submit(APPLICATION);

        // the raw rows of the runtime table, the history tables and their byte arrays
        List<StoredRows.Row> rows = storedRows.of(id);
        for (String name : List.of("iban", "birthDate", "applicantProfile", "passportNumber")) {
            assertThat(rows)
                    .filteredOn(row -> row.variable().equals(name))
                    .as("rows of %s in the runtime table and the history", name)
                    .hasSizeGreaterThanOrEqualTo(3) // ACT_RU_VARIABLE, ACT_HI_VARINST, ACT_HI_DETAIL
                    .allSatisfy(row -> {
                        assertThat(row.text()).startsWith("$operaton-ve$1$demo-2026-01");
                        assertThat(row.longValue()).isNull();
                        assertThat(row.bytes()).isGreaterThan(30);
                        assertThat(row.bytesHex()).startsWith("4f5645"); // envelope magic "OVE"
                    });
        }
        String everything = everythingStoredOf(id);
        assertThat(everything)
                .doesNotContain(IBAN, "DE89", "Lovelace", "Analytical Street", "Berlin", "TAXID-12", "C01X00T47")
                .doesNotContain("1990", "661478400000");
        // data that is not personal stays readable, also in the history
        assertThat(rows)
                .filteredOn(row -> row.variable().equals("product"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.text()).isEqualTo("Giro account"));
        assertThat(rows).filteredOn(row -> row.variable().equals("ibanValid")).isNotEmpty();

        // through the engine API everything is plain
        Map<String, Object> variables = runtimeService.getVariables(id);
        assertThat(variables.get("iban")).isEqualTo(IBAN);
        assertThat(variables.get("passportNumber")).isEqualTo("C01X00T47");
        assertThat(variables.get("product")).isEqualTo("Giro account");
        assertThat(variables.get("ibanValid")).isEqualTo(true);
        assertThat(((Date) variables.get("birthDate")).toInstant().toString()).startsWith("1990-12-10");
        Applicant profile = (Applicant) variables.get("applicantProfile");
        assertThat(profile.getName()).isEqualTo("Ada Lovelace");
        assertThat(profile.getTaxId()).isEqualTo("TAXID-12-345-678");
        ObjectValue serialized = runtimeService.getVariableTyped(id, "applicantProfile", false);
        assertThat(serialized.getValueSerialized()).contains("Analytical Street");
    }

    @Test
    void theClerkSeesThePlainDataInTheTaskAndTheHistoryStaysEncrypted() {
        String id = service.submit(APPLICATION);
        String taskId = taskService
                .createTaskQuery()
                .processInstanceId(id)
                .singleResult()
                .getId();
        assertThat(taskService.getVariable(taskId, "iban")).isEqualTo(IBAN);

        taskService.complete(taskId, Variables.createVariables().putValue("approved", true));

        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(id)
                        .finished()
                        .count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ACT_RU_VARIABLE where PROC_INST_ID_ = ?", Long.class, id))
                .isZero();
        HistoricVariableInstance iban = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(id)
                .variableName("iban")
                .singleResult();
        assertThat(iban.getValue()).isEqualTo(IBAN);
        assertThat(historyService
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(id)
                        .variableName("birthDate")
                        .singleResult()
                        .getValue())
                .isInstanceOf(Date.class);
        assertThat(historyService
                        .createHistoricDetailQuery()
                        .variableUpdates()
                        .processInstanceId(id)
                        .count())
                .isGreaterThanOrEqualTo(5);
        // after the instance has ended, the history rows are all that is left: still ciphertext
        assertThat(everythingStoredOf(id)).doesNotContain(IBAN, "Lovelace", "TAXID-12", "C01X00T47");
        assertThat(storedRows.of(id))
                .filteredOn(row -> row.variable().equals("iban"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.text()).startsWith("$operaton-ve$1$"));
    }

    @Test
    void anInvalidIbanIsRejectedAndStillNeverStoredInPlaintext() {
        String id = service.submit(new ApplicationRequest(
                "Grace Hopper",
                "1 Navy Yard",
                "Arlington",
                "TAXID-99",
                "DE00370400440532013000",
                LocalDate.of(1906, 12, 9),
                null,
                "Giro account"));

        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(id)
                        .finished()
                        .count())
                .isEqualTo(1);
        assertThat(historyService
                        .createHistoricActivityInstanceQuery()
                        .processInstanceId(id)
                        .activityId("application-rejected")
                        .count())
                .isEqualTo(1);
        assertThat(everythingStoredOf(id)).doesNotContain("DE0037", "Hopper", "TAXID-99");
        assertThat(historyService
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(id)
                        .variableName("iban")
                        .singleResult()
                        .getValue())
                .isEqualTo("DE00370400440532013000");
    }

    @Test
    void aTamperedCiphertextIsDetectedWhenTheApplicationIsRead() {
        String id = service.submit(APPLICATION);
        String byteArrayId = jdbc.queryForObject(
                "select BYTEARRAY_ID_ from ACT_RU_VARIABLE where PROC_INST_ID_ = ? and NAME_ = 'iban'",
                String.class,
                id);
        byte[] ciphertext =
                jdbc.queryForObject("select BYTES_ from ACT_GE_BYTEARRAY where ID_ = ?", byte[].class, byteArrayId);
        byte[] tampered = ciphertext.clone();
        tampered[tampered.length - 3] ^= 0x10;

        jdbc.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", tampered, byteArrayId);
        assertThatThrownBy(() -> runtimeService.getVariable(id, "iban"))
                .hasMessageContaining("Integrity check failed")
                .hasMessageContaining("'iban'");

        jdbc.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", ciphertext, byteArrayId);
        assertThat(runtimeService.getVariable(id, "iban")).isEqualTo(IBAN);
    }

    @Test
    void theRestApiReturnsPlainDataAndShowsTheStoredRows() {
        RestClient client = RestClient.create("http://localhost:" + port);

        @SuppressWarnings("unchecked")
        Map<String, String> started = client.post()
                .uri("/api/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name", "Alan Turing",
                        "street", "Hut 8",
                        "city", "Bletchley",
                        "taxId", "TAXID-REST-1",
                        "iban", IBAN,
                        "birthDate", "1912-06-23",
                        "passportNumber", "REST-PASSPORT",
                        "product", "Savings account"))
                .retrieve()
                .body(Map.class);
        String id = started.get("processInstanceId");

        String view = client.get().uri("/api/applications/{id}", id).retrieve().body(String.class);
        assertThat(view).contains(IBAN).contains("Alan Turing").contains("REST-PASSPORT");

        String stored =
                client.get().uri("/api/applications/{id}/stored", id).retrieve().body(String.class);
        assertThat(stored).contains("$operaton-ve$1$demo-2026-01").contains("4f5645");
        assertThat(stored).doesNotContain(IBAN).doesNotContain("Alan Turing").doesNotContain("REST-PASSPORT");
    }
}

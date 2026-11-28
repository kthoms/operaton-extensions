/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.history.HistoricDetail;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.history.HistoricVariableUpdate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.VariableInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.operaton.bpm.engine.variable.value.BytesValue;
import org.operaton.bpm.engine.variable.value.DateValue;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.bpm.engine.variable.value.LongValue;
import org.operaton.bpm.engine.variable.value.StringValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

@Deployment(resources = "processes/applicant.bpmn20.xml")
class VariableEncryptionEngineTest {

    static final String IBAN = "DE89370400440532013000";
    static final String PASSPORT = "C01X00T47";
    static final Date BIRTH_DATE = new Date(631152000000L); // 1990-01-01
    static final byte[] SCAN = "SCANNED-PASSPORT-PAGE-0815".getBytes(StandardCharsets.UTF_8);

    final VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();

    @RegisterExtension
    final ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .configurator(cfg -> {
                plugin.setKeys(Map.of("k1", TestKeys.KEY_1));
                plugin.setActiveKeyId("k1");
                plugin.setVariableNames(
                        List.of("iban", "birthDate", "score", "ratio", "consent", "payload", "document"));
                cfg.getProcessEnginePlugins().add(plugin);
            })
            .closeEngineAfterEachTest()
            .build();

    RawRows raw;

    @BeforeEach
    void setUp() {
        raw = new RawRows(
                ((ProcessEngineConfigurationImpl) engine.getProcessEngine().getProcessEngineConfiguration())
                        .getDataSource());
    }

    private ProcessInstance startWithPersonalData() {
        VariableMap variables = Variables.createVariables()
                .putValue("iban", IBAN)
                .putValue("birthDate", BIRTH_DATE)
                .putValue("score", 4711L)
                .putValue("ratio", 0.8125d)
                .putValue("consent", true)
                .putValue("payload", SCAN)
                .putValueTyped(
                        "document",
                        Variables.fileValue("passport.txt")
                                .file(SCAN)
                                .mimeType("text/plain")
                                .encoding("UTF-8")
                                .create())
                .putValue("note", "visible note")
                .putValue("noteCount", 42L);
        return engine.getRuntimeService().startProcessInstanceByKey("applicant-process", variables);
    }

    private static void assertEncryptedRow(Map<String, Object> row, RawRows raw) {
        assertThat((String) row.get("TEXT_")).startsWith(EncryptedMarker.TEXT_PREFIX);
        assertThat(row.get("TEXT2_")).isNull();
        assertThat(row.get("LONG_")).isNull();
        assertThat(row.get("DOUBLE_")).isNull();
        assertThat(row.get("BYTEARRAY_ID_")).isNotNull();
        byte[] envelope = raw.bytes(row.get("BYTEARRAY_ID_"));
        assertThat(envelope[0]).isEqualTo((byte) 'O');
        assertThat(envelope[3]).isEqualTo((byte) 1);
    }

    /** The marker prefix, spelled out so that a change of the stored format fails this test visibly. */
    static final class EncryptedMarker {
        static final String TEXT_PREFIX = "$operaton-ve$1$k1";

        private EncryptedMarker() {}
    }

    @Test
    void designatedVariablesAreStoredAsCiphertext() {
        startWithPersonalData();

        for (String name : List.of("iban", "birthDate", "score", "ratio", "consent", "payload", "document")) {
            assertEncryptedRow(raw.runtimeVariable(name), raw);
        }
        String everything = raw.everythingStored();
        assertThat(everything)
                .doesNotContain(IBAN, "DE89", "0532", "SCANNED-PASSPORT", "passport.txt", "text/plain", "4711")
                .doesNotContain("631152000000");
        // the serializer names (types) stay what they are
        assertThat(raw.runtimeVariable("iban").get("TYPE_")).isEqualTo("string");
        assertThat(raw.runtimeVariable("birthDate").get("TYPE_")).isEqualTo("date");
        assertThat(raw.runtimeVariable("score").get("TYPE_")).isEqualTo("long");
        assertThat(raw.runtimeVariable("document").get("TYPE_")).isEqualTo("file");
    }

    @Test
    void nonDesignatedVariablesAreStoredUntouched() {
        startWithPersonalData();

        Map<String, Object> note = raw.runtimeVariable("note");
        assertThat(note.get("TEXT_")).isEqualTo("visible note");
        assertThat(note.get("BYTEARRAY_ID_")).isNull();
        Map<String, Object> count = raw.runtimeVariable("noteCount");
        assertThat(count.get("LONG_")).isEqualTo(42L);
        assertThat(count.get("BYTEARRAY_ID_")).isNull();
        assertThat(raw.historicVariable("note").get("TEXT_")).isEqualTo("visible note");
    }

    @Test
    void theEngineApiReturnsTheOriginalValues() throws IOException {
        ProcessInstance instance = startWithPersonalData();
        var runtime = engine.getRuntimeService();

        assertThat(runtime.getVariable(instance.getId(), "iban")).isEqualTo(IBAN);
        assertThat(runtime.getVariable(instance.getId(), "birthDate")).isEqualTo(BIRTH_DATE);
        assertThat(runtime.getVariable(instance.getId(), "score")).isEqualTo(4711L);
        assertThat(runtime.getVariable(instance.getId(), "ratio")).isEqualTo(0.8125d);
        assertThat(runtime.getVariable(instance.getId(), "consent")).isEqualTo(true);
        assertThat((byte[]) runtime.getVariable(instance.getId(), "payload")).isEqualTo(SCAN);
        assertThat(runtime.getVariable(instance.getId(), "note")).isEqualTo("visible note");

        StringValue iban = runtime.getVariableTyped(instance.getId(), "iban");
        assertThat(iban.getType()).isEqualTo(ValueType.STRING);
        assertThat(iban.getValue()).isEqualTo(IBAN);
        assertThat(iban).isNotInstanceOf(EncryptedValue.class);
        DateValue birthDate = runtime.getVariableTyped(instance.getId(), "birthDate");
        assertThat(birthDate.getType()).isEqualTo(ValueType.DATE);
        LongValue score = runtime.getVariableTyped(instance.getId(), "score");
        assertThat(score.getType()).isEqualTo(ValueType.LONG);
        BytesValue payload = runtime.getVariableTyped(instance.getId(), "payload");
        assertThat(payload.getType()).isEqualTo(ValueType.BYTES);
        FileValue document = runtime.getVariableTyped(instance.getId(), "document");
        assertThat(document.getFilename()).isEqualTo("passport.txt");
        assertThat(document.getMimeType()).isEqualTo("text/plain");
        assertThat(document.getEncoding()).isEqualTo("UTF-8");
        assertThat(document.getValue().readAllBytes()).isEqualTo(SCAN);

        Map<String, Object> all = runtime.getVariables(instance.getId());
        assertThat(all)
                .containsEntry("iban", IBAN)
                .containsEntry("score", 4711L)
                .containsEntry("note", "visible note");
    }

    @Test
    void variableInstanceQueriesAndTaskVariablesAreDecrypted() {
        ProcessInstance instance = startWithPersonalData();

        VariableInstance variable = engine.getRuntimeService()
                .createVariableInstanceQuery()
                .processInstanceIdIn(instance.getId())
                .variableName("iban")
                .singleResult();
        assertThat(variable.getValue()).isEqualTo(IBAN);
        assertThat(variable.getTypeName()).isEqualTo("string");

        String taskId = engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getId();
        assertThat(engine.getTaskService().getVariable(taskId, "iban")).isEqualTo(IBAN);
        engine.getTaskService().complete(taskId, Variables.createVariables().putValue("score", 1L));
        assertThat(engine.getHistoryService()
                        .createHistoricVariableInstanceQuery()
                        .variableName("score")
                        .singleResult()
                        .getValue())
                .isEqualTo(1L);
    }

    @Test
    void historyHoldsTheSameCiphertextAndReadsBackDecrypted() {
        ProcessInstance instance = startWithPersonalData();
        engine.getRuntimeService().setVariable(instance.getId(), "iban", "FR1420041010050500013M02606");
        String taskId = engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getId();
        engine.getTaskService().complete(taskId);
        var history = engine.getHistoryService();

        // the process has ended: runtime rows are gone, history remains
        assertThat(raw.query("select * from ACT_RU_VARIABLE")).isEmpty();
        HistoricVariableInstance historic = history.createHistoricVariableInstanceQuery()
                .variableName("iban")
                .singleResult();
        assertThat(historic.getValue()).isEqualTo("FR1420041010050500013M02606");
        assertThat(historic.getTypeName()).isEqualTo("string");
        assertThat(history.createHistoricVariableInstanceQuery()
                        .variableName("birthDate")
                        .singleResult()
                        .getValue())
                .isEqualTo(BIRTH_DATE);

        Map<String, Object> historicRow = raw.historicVariable("iban");
        assertThat((String) historicRow.get("TEXT_")).startsWith("$operaton-ve$1$k1");
        assertThat(historicRow.get("BYTEARRAY_ID_")).isNotNull();

        List<HistoricDetail> details = history.createHistoricDetailQuery()
                .variableUpdates()
                .processInstanceId(instance.getId())
                .orderByVariableName()
                .asc()
                .list();
        List<Object> ibanUpdates = new ArrayList<>();
        for (HistoricDetail detail : details) {
            HistoricVariableUpdate update = (HistoricVariableUpdate) detail;
            if (update.getVariableName().equals("iban")) {
                ibanUpdates.add(update.getValue());
            }
        }
        assertThat(ibanUpdates).containsExactlyInAnyOrder(IBAN, "FR1420041010050500013M02606");
        raw.query("select * from ACT_HI_DETAIL where NAME_ = 'iban'").forEach(row -> {
            assertThat((String) row.get("TEXT_")).startsWith("$operaton-ve$1$k1");
            assertThat(row.get("BYTEARRAY_ID_")).isNotNull();
        });

        assertThat(raw.everythingStored()).doesNotContain(IBAN, "FR1420041010050500013M02606", "SCANNED-PASSPORT");
        // the plain variable stays readable in the history tables
        assertThat(raw.everythingStored()).contains("visible note");
    }

    @Test
    void updatingAndRemovingVariablesKeepsTheRowsEncrypted() {
        ProcessInstance instance = startWithPersonalData();
        var runtime = engine.getRuntimeService();
        Object before = raw.runtimeVariable("iban").get("BYTEARRAY_ID_");
        byte[] ciphertextBefore = raw.bytes(before);

        runtime.setVariable(instance.getId(), "iban", IBAN); // same value again: a new random IV
        runtime.setVariable(instance.getId(), "score", 99L);

        assertThat(runtime.getVariable(instance.getId(), "iban")).isEqualTo(IBAN);
        assertThat(runtime.getVariable(instance.getId(), "score")).isEqualTo(99L);
        Map<String, Object> row = raw.runtimeVariable("iban");
        assertEncryptedRow(row, raw);
        assertThat(raw.bytes(row.get("BYTEARRAY_ID_"))).isNotEqualTo(ciphertextBefore);

        runtime.removeVariable(instance.getId(), "iban");
        assertThat(runtime.getVariable(instance.getId(), "iban")).isNull();
        assertThat(raw.query("select * from ACT_RU_VARIABLE where NAME_ = 'iban'"))
                .isEmpty();
        assertThat(raw.query("select * from ACT_GE_BYTEARRAY where ID_ = ?", before))
                .isEmpty();
    }

    @Test
    void aVariableThatChangesItsTypeStaysEncrypted() {
        ProcessInstance instance = startWithPersonalData();
        var runtime = engine.getRuntimeService();

        runtime.setVariable(instance.getId(), "score", "now a string");

        assertThat(runtime.getVariable(instance.getId(), "score")).isEqualTo("now a string");
        assertEncryptedRow(raw.runtimeVariable("score"), raw);
        assertThat(raw.runtimeVariable("score").get("TYPE_")).isEqualTo("string");
    }

    @Test
    void nullsAndLongStringsOfDesignatedVariablesAreEncryptedToo() {
        String longText = "long-personal-text ".repeat(500); // 9500 characters: more than the text column holds
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables()
                                .putValueTyped("iban", Variables.stringValue(null))
                                .putValue("payload", new byte[0])
                                .putValue("ratio", 0d));
        var runtime = engine.getRuntimeService();
        assertThat(runtime.getVariable(instance.getId(), "iban")).isNull();
        assertEncryptedRow(raw.runtimeVariable("iban"), raw);

        runtime.setVariable(instance.getId(), "iban", longText);

        assertThat(runtime.getVariable(instance.getId(), "iban")).isEqualTo(longText);
        assertThat(raw.everythingStored()).doesNotContain("long-personal-text");
        assertThat((byte[]) runtime.getVariable(instance.getId(), "payload")).isEmpty();
    }

    @Test
    void taggedValuesAreEncryptedWhateverTheNameIs() throws IOException {
        VariableMap variables = Variables.createVariables()
                .putValueTyped("passport", EncryptedVariables.encrypted(PASSPORT))
                .putValueTyped("tagged", EncryptedVariables.encrypted(Variables.stringValue("TAGGED-TEXT")))
                .putValueTyped("taggedNumber", EncryptedVariables.encrypted(123456789L))
                .putValueTyped("taggedInt", EncryptedVariables.encrypted(Variables.integerValue(7)))
                .putValueTyped("taggedShort", EncryptedVariables.encrypted((short) 3))
                .putValueTyped("taggedFlag", EncryptedVariables.encrypted(Boolean.TRUE))
                .putValueTyped("taggedDouble", EncryptedVariables.encrypted(2.5d))
                .putValueTyped("taggedDate", EncryptedVariables.encrypted(BIRTH_DATE))
                .putValueTyped("taggedBytes", EncryptedVariables.encrypted(SCAN))
                .putValueTyped(
                        "taggedFile",
                        EncryptedVariables.encrypted(
                                Variables.fileValue("secret.bin").file(SCAN).create()))
                .putValue("plain", "PLAIN-TEXT");
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("applicant-process", variables);
        var runtime = engine.getRuntimeService();

        for (String name : variables.keySet()) {
            if (name.equals("plain")) {
                assertThat(raw.runtimeVariable(name).get("TEXT_")).isEqualTo("PLAIN-TEXT");
            } else {
                assertEncryptedRow(raw.runtimeVariable(name), raw);
            }
        }
        assertThat(raw.everythingStored())
                .doesNotContain(PASSPORT, "TAGGED-TEXT", "123456789", "secret.bin", "SCANNED-PASSPORT");
        assertThat(runtime.getVariable(instance.getId(), "passport")).isEqualTo(PASSPORT);
        assertThat(runtime.getVariable(instance.getId(), "tagged")).isEqualTo("TAGGED-TEXT");
        assertThat(runtime.getVariable(instance.getId(), "taggedNumber")).isEqualTo(123456789L);
        assertThat(runtime.getVariable(instance.getId(), "taggedInt")).isEqualTo(7);
        assertThat(runtime.getVariable(instance.getId(), "taggedShort")).isEqualTo((short) 3);
        assertThat(runtime.getVariable(instance.getId(), "taggedFlag")).isEqualTo(true);
        assertThat(runtime.getVariable(instance.getId(), "taggedDouble")).isEqualTo(2.5d);
        assertThat(runtime.getVariable(instance.getId(), "taggedDate")).isEqualTo(BIRTH_DATE);
        assertThat((byte[]) runtime.getVariable(instance.getId(), "taggedBytes"))
                .isEqualTo(SCAN);
        FileValue file = runtime.getVariableTyped(instance.getId(), "taggedFile");
        assertThat(file.getFilename()).isEqualTo("secret.bin");
        assertThat(file.getValue().readAllBytes()).isEqualTo(SCAN);
        TypedValue read = runtime.getVariableTyped(instance.getId(), "passport");
        assertThat(EncryptedVariables.isEncrypted(read)).isFalse();
        // history of a tagged value is encrypted as well
        assertThat(raw.historicVariable("passport").get("TEXT_")).asString().startsWith("$operaton-ve$1$");
    }

    @Test
    void theTagAppliesToOneWriteOnly() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables().putValueTyped("passport", EncryptedVariables.encrypted(PASSPORT)));
        assertEncryptedRow(raw.runtimeVariable("passport"), raw);

        engine.getRuntimeService().setVariable(instance.getId(), "passport", PASSPORT);

        assertThat(raw.runtimeVariable("passport").get("TEXT_")).isEqualTo(PASSPORT);
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "passport"))
                .isEqualTo(PASSPORT);
    }

    @Test
    void invalidTagsFailLoudly() {
        assertThatThrownBy(() -> EncryptedVariables.encrypted((Object) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptedVariables.encrypted((TypedValue) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptedVariables.encrypted(Variables.untypedNullValue()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name pattern");
        TypedValue tagged = EncryptedVariables.encrypted("x");
        assertThat(EncryptedVariables.encrypted(tagged)).isSameAs(tagged);
    }

    @Test
    void queryingByTheValueOfAnEncryptedVariableFailsLoudly() {
        startWithPersonalData();
        var runtime = engine.getRuntimeService();

        assertThatThrownBy(() -> runtime.createProcessInstanceQuery()
                        .variableValueEquals("iban", IBAN)
                        .list())
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("iban")
                .hasMessageContaining("queries");
        assertThatThrownBy(() -> runtime.createVariableInstanceQuery()
                        .variableValueEquals("score", 4711L)
                        .list())
                .isInstanceOf(VariableEncryptionException.class);
        // a variable that is not encrypted can be queried as before, and by name
        assertThat(runtime.createProcessInstanceQuery()
                        .variableValueEquals("note", "visible note")
                        .count())
                .isEqualTo(1);
        assertThat(runtime.createVariableInstanceQuery().variableName("iban").count())
                .isEqualTo(1);
    }

    @Test
    void aModifiedCiphertextIsDetectedAndNeverReadAsPlaintext() {
        ProcessInstance instance = startWithPersonalData();
        Object byteArrayId = raw.runtimeVariable("iban").get("BYTEARRAY_ID_");
        byte[] envelope = raw.bytes(byteArrayId);
        byte[] tampered = envelope.clone();
        tampered[tampered.length - 5] ^= 0x40;
        raw.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", tampered, byteArrayId);

        assertThatThrownBy(() -> engine.getRuntimeService().getVariable(instance.getId(), "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Integrity check failed")
                .hasMessageContaining("'iban'");
        assertThatThrownBy(() -> engine.getRuntimeService().getVariables(instance.getId()))
                .isInstanceOf(VariableEncryptionException.class);
        // other variables are not affected
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "note"))
                .isEqualTo("visible note");

        // restoring the original bytes restores access
        raw.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", envelope, byteArrayId);
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "iban"))
                .isEqualTo(IBAN);
    }

    @Test
    void aCiphertextCopiedToAnotherVariableIsRejected() {
        ProcessInstance instance = startWithPersonalData();
        Object ibanBytes = raw.runtimeVariable("iban").get("BYTEARRAY_ID_");
        // 'note' is not designated: plaintext. Make it look like an encrypted iban.
        Map<String, Object> noteRow = raw.runtimeVariable("note");
        Object borrowed = java.util.UUID.randomUUID().toString();
        raw.update(
                "insert into ACT_GE_BYTEARRAY (ID_, REV_, NAME_, BYTES_, GENERATED_) values (?, 1, 'note', ?, 0)",
                borrowed,
                raw.bytes(ibanBytes));
        raw.update(
                "update ACT_RU_VARIABLE set TEXT_ = ?, BYTEARRAY_ID_ = ? where ID_ = ?",
                raw.runtimeVariable("iban").get("TEXT_"),
                borrowed,
                noteRow.get("ID_"));

        assertThatThrownBy(() -> engine.getRuntimeService().getVariable(instance.getId(), "note"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Integrity check failed")
                .hasMessageContaining("'note'");
    }

    @Test
    void aDesignatedVariableReplacedByPlaintextIsRejected() {
        ProcessInstance instance = startWithPersonalData();
        raw.update("update ACT_RU_VARIABLE set TEXT_ = 'DE00000000000000000000' where NAME_ = 'iban'");

        assertThatThrownBy(() -> engine.getRuntimeService().getVariable(instance.getId(), "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("designated for encryption but is stored in plaintext");
    }

    @Test
    void aMarkedRowWithoutItsDataIsRejected() {
        ProcessInstance instance = startWithPersonalData();
        raw.update("update ACT_RU_VARIABLE set BYTEARRAY_ID_ = null where NAME_ = 'iban'");

        assertThatThrownBy(() -> engine.getRuntimeService().getVariable(instance.getId(), "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("missing or truncated");
    }

    @Test
    void aKeyIdInTheTextColumnThatDoesNotMatchTheEnvelopeIsRejected() {
        ProcessInstance instance = startWithPersonalData();
        raw.update("update ACT_RU_VARIABLE set TEXT_ = '$operaton-ve$1$other' where NAME_ = 'iban'");

        assertThatThrownBy(() -> engine.getRuntimeService().getVariable(instance.getId(), "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void aTamperedHistoryRowIsDetectedToo() {
        ProcessInstance instance = startWithPersonalData();
        Object byteArrayId = raw.historicVariable("iban").get("BYTEARRAY_ID_");
        byte[] tampered = raw.bytes(byteArrayId).clone();
        tampered[tampered.length - 1] ^= 1;
        raw.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", tampered, byteArrayId);

        // the engine's queries load values leniently (they log the failure and set the error message,
        // the same as for a variable whose class is missing): the value is never a plaintext fallback
        HistoricVariableInstance historic = engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("iban")
                .singleResult();
        assertThat(historic.getErrorMessage())
                .contains("Integrity check failed")
                .contains("'iban'");
        assertThat(historic.getValue()).isNull();
    }

    @Test
    void aTamperedRowIsReportedByQueriesThroughTheErrorMessage() {
        ProcessInstance instance = startWithPersonalData();
        Object byteArrayId = raw.runtimeVariable("iban").get("BYTEARRAY_ID_");
        raw.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", new byte[] {9, 9, 9}, byteArrayId);

        VariableInstance variable = engine.getRuntimeService()
                .createVariableInstanceQuery()
                .processInstanceIdIn(instance.getId())
                .variableName("iban")
                .singleResult();

        assertThat(variable.getErrorMessage()).contains("missing or truncated").contains("'iban'");
        assertThat(variable.getValue()).isNull();
    }

    @Test
    void aFailingDecryptionFailsTheCommandInsteadOfFallingBack() {
        ProcessInstance instance = startWithPersonalData();
        Object byteArrayId = raw.runtimeVariable("iban").get("BYTEARRAY_ID_");
        raw.update("update ACT_GE_BYTEARRAY set BYTES_ = ? where ID_ = ?", new byte[] {1, 2, 3}, byteArrayId);
        String taskId = engine.getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getId();

        // reading the variable in a command (here: completing with a variable lookup) fails loudly
        assertThatThrownBy(() -> engine.getTaskService().getVariable(taskId, "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("missing or truncated");
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count())
                .isEqualTo(1);
    }

    @Test
    void manyThreadsEncryptAndDecryptConcurrently() throws Exception {
        int threads = 8;
        int perThread = 15;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    List<String> problems = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        String secret = "IBAN-" + thread + "-" + i;
                        ProcessInstance instance = engine.getRuntimeService()
                                .startProcessInstanceByKey(
                                        "applicant-process",
                                        Variables.createVariables().putValue("iban", secret));
                        Object read = engine.getRuntimeService().getVariable(instance.getId(), "iban");
                        if (!secret.equals(read)) {
                            problems.add(secret + " -> " + read);
                        }
                    }
                    return problems;
                }));
            }
            for (Future<List<String>> future : futures) {
                assertThat(future.get()).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(raw.everythingStored()).doesNotContain("IBAN-");
        assertThat(raw.query("select * from ACT_RU_VARIABLE")).hasSize(threads * perThread);
    }

    @Test
    void pluginSettingsAreValidatedAtStartup() {
        assertThatThrownBy(() -> new VariableEncryptionPlugin().validate())
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("No key source");
        VariableEncryptionPlugin both = new VariableEncryptionPlugin();
        both.setKeys(Map.of("k1", TestKeys.KEY_1));
        both.setActiveKeyId("k1");
        both.setKeyStore("/some/file.p12");
        assertThatThrownBy(both::validate)
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("More than one key source");
        VariableEncryptionPlugin nothing = new VariableEncryptionPlugin();
        nothing.setKeys(Map.of("k1", TestKeys.KEY_1));
        nothing.setActiveKeyId("k1");
        nothing.setEncryptMarkedValues(false);
        assertThatThrownBy(() -> nothing.preInit(null))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Nothing would be encrypted");
        VariableEncryptionPlugin badPattern = new VariableEncryptionPlugin();
        badPattern.setKeys(Map.of("k1", TestKeys.KEY_1));
        badPattern.setActiveKeyId("k1");
        badPattern.setVariableNames(List.of("regex:["));
        assertThatThrownBy(() -> badPattern.preInit(null))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Invalid variable name pattern");
        assertThat(ByteArrayInputStream.nullInputStream()).isNotNull();
    }
}

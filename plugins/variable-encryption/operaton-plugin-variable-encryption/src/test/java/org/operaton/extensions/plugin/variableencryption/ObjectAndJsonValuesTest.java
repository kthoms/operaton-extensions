/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.spin.plugin.variable.SpinValues.jsonValue;
import static org.operaton.spin.plugin.variable.SpinValues.xmlValue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.history.HistoricVariableUpdate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.spin.plugin.impl.SpinProcessEnginePlugin;
import org.operaton.spin.plugin.variable.value.JsonValue;
import org.operaton.spin.plugin.variable.value.XmlValue;

/** JSON (Spin), object and Java-serialized values: written encrypted, read back, mutated in place. */
@Deployment(resources = {"processes/applicant.bpmn20.xml", "processes/mutating.bpmn20.xml"})
class ObjectAndJsonValuesTest {

    static final String JSON = Variables.SerializationDataFormats.JSON.getName();
    static final Applicant ADA = new Applicant("Ada Lovelace", "London", "TAXID-12-345-678");

    final VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();

    @RegisterExtension
    final ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .configurator(cfg -> {
                plugin.setKeys(Map.of("k1", TestKeys.KEY_1));
                plugin.setActiveKeyId("k1");
                plugin.setVariableNames(List.of("applicant*"));
                cfg.setJavaSerializationFormatEnabled(true);
                // the Spin plugin is registered after ours on purpose: ours installs once the engine is built
                cfg.getProcessEnginePlugins().add(plugin);
                cfg.getProcessEnginePlugins().add(new SpinProcessEnginePlugin());
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

    private ObjectValue profile(Applicant applicant) {
        return Variables.objectValue(applicant).serializationDataFormat(JSON).create();
    }

    @Test
    void anObjectValueSerializedAsJsonIsEncryptedAndReadBackAsTheObject() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables().putValueTyped("applicantProfile", profile(ADA)));

        Map<String, Object> row = raw.runtimeVariable("applicantProfile");
        assertThat((String) row.get("TEXT_")).startsWith("$operaton-ve$1$k1");
        assertThat(row.get("TEXT2_"))
                .as("the object type name is encrypted too")
                .isNull();
        assertThat(row.get("TYPE_")).isEqualTo("spin://application/json");
        assertThat(raw.everythingStored()).doesNotContain("Lovelace", "TAXID-12", "London", Applicant.class.getName());

        ObjectValue value = engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantProfile");
        assertThat(value.isDeserialized()).isTrue();
        assertThat(value.getValue(Applicant.class)).isEqualTo(ADA);
        assertThat(value.getObjectTypeName()).isEqualTo(Applicant.class.getName());
        assertThat(value.getSerializationDataFormat()).isEqualTo(JSON);

        ObjectValue serialized =
                engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantProfile", false);
        assertThat(serialized.isDeserialized()).isFalse();
        assertThat(serialized.getValueSerialized()).contains("Lovelace").contains("TAXID-12-345-678");
        assertThat(serialized.getObjectTypeName()).isEqualTo(Applicant.class.getName());
    }

    @Test
    void aSerializedObjectValueIsEncryptedWithoutBeingDeserialized() {
        String json = "{\"name\":\"Grace Hopper\",\"city\":\"Arlington\",\"taxId\":\"TAXID-99\"}";
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables()
                                .putValueTyped(
                                        "applicantProfile",
                                        Variables.serializedObjectValue(json)
                                                .serializationDataFormat(JSON)
                                                .objectTypeName(Applicant.class.getName())
                                                .create()));

        assertThat(raw.everythingStored()).doesNotContain("Hopper", "TAXID-99");
        Applicant read = (Applicant) engine.getRuntimeService().getVariable(instance.getId(), "applicantProfile");
        assertThat(read.getName()).isEqualTo("Grace Hopper");
    }

    @Test
    void javaSerializedObjectsAreEncrypted() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables()
                                .putValueTyped(
                                        "applicantJava",
                                        Variables.objectValue(ADA)
                                                .serializationDataFormat(Variables.SerializationDataFormats.JAVA)
                                                .create()));

        assertThat(raw.runtimeVariable("applicantJava").get("TYPE_")).isEqualTo("serializable");
        assertThat(raw.everythingStored()).doesNotContain("Lovelace", "TAXID-12");
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "applicantJava"))
                .isEqualTo(ADA);
    }

    @Test
    void spinJsonAndXmlValuesAreEncrypted() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables()
                                .putValueTyped(
                                        "applicantJson",
                                        jsonValue("{\"iban\":\"DE89370400440532013000\"}")
                                                .create())
                                .putValueTyped(
                                        "applicantXml",
                                        xmlValue("<applicant><taxId>TAXID-XML-77</taxId></applicant>")
                                                .create()));

        assertThat(raw.runtimeVariable("applicantJson").get("TYPE_")).isEqualTo("json");
        assertThat(raw.runtimeVariable("applicantXml").get("TYPE_")).isEqualTo("xml");
        assertThat(raw.everythingStored()).doesNotContain("DE89370400440532013000", "TAXID-XML-77");

        JsonValue json = engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantJson");
        assertThat(json.getValue().prop("iban").stringValue()).isEqualTo("DE89370400440532013000");
        XmlValue xml = engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantXml");
        assertThat(xml.getValue().childElement("taxId").textContent()).isEqualTo("TAXID-XML-77");
        JsonValue serialized = engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantJson", false);
        assertThat(serialized.getValueSerialized()).contains("DE89370400440532013000");
    }

    @Test
    void taggedObjectsAndSpinValuesAreEncryptedUnderAnyName() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables()
                                .putValueTyped("tagged", EncryptedVariables.encrypted(profile(ADA)))
                                .putValueTyped(
                                        "taggedJson",
                                        EncryptedVariables.encrypted(jsonValue("{\"secret\":\"JSON-SECRET\"}")
                                                .create()))
                                .putValueTyped(
                                        "taggedXml",
                                        EncryptedVariables.encrypted(
                                                xmlValue("<a>XML-SECRET</a>").create()))
                                .putValueTyped("taggedPojo", EncryptedVariables.encrypted((Object) ADA)));

        for (String name : List.of("tagged", "taggedJson", "taggedXml", "taggedPojo")) {
            assertThat((String) raw.runtimeVariable(name).get("TEXT_")).startsWith("$operaton-ve$1$k1");
        }
        assertThat(raw.everythingStored()).doesNotContain("Lovelace", "JSON-SECRET", "XML-SECRET");
        assertThat(((ObjectValue) engine.getRuntimeService().getVariableTyped(instance.getId(), "tagged"))
                        .getValue(Applicant.class))
                .isEqualTo(ADA);
        assertThat(((JsonValue) engine.getRuntimeService().getVariableTyped(instance.getId(), "taggedJson"))
                        .getValue()
                        .prop("secret")
                        .stringValue())
                .isEqualTo("JSON-SECRET");
    }

    @Test
    void readingADeserializedObjectIsNotAChangeButChangingItIsDetectedAndStaysEncrypted() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "mutating-process",
                        Variables.createVariables().putValueTyped("applicantProfile", profile(ADA)));

        // ReadOnlyDelegate only read the object: no update. MutatingDelegate changed it in place: one update.
        List<Object> updates = engine
                .getHistoryService()
                .createHistoricDetailQuery()
                .variableUpdates()
                .processInstanceId(instance.getId())
                .orderPartiallyByOccurrence()
                .asc()
                .list()
                .stream()
                .filter(detail ->
                        ((HistoricVariableUpdate) detail).getVariableName().equals("applicantProfile"))
                .map(detail -> ((HistoricVariableUpdate) detail).getValue())
                .toList();
        assertThat(updates).hasSize(2);
        assertThat(((Applicant) updates.get(0)).getCity()).isEqualTo("London");
        assertThat(((Applicant) updates.get(1)).getCity()).isEqualTo("Hamburg");

        Applicant current = (Applicant) engine.getRuntimeService().getVariable(instance.getId(), "applicantProfile");
        assertThat(current.getCity()).isEqualTo("Hamburg");
        assertThat((String) raw.runtimeVariable("applicantProfile").get("TEXT_"))
                .startsWith("$operaton-ve$1$k1");
        assertThat(raw.everythingStored()).doesNotContain("Lovelace", "Hamburg", "London");
        raw.query("select * from ACT_HI_DETAIL where NAME_ = 'applicantProfile'")
                .forEach(row -> assertThat((String) row.get("TEXT_")).startsWith("$operaton-ve$1$k1"));
    }

    @Test
    void readingAnUnchangedObjectInLaterCommandsCreatesNoHistory() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables().putValueTyped("applicantProfile", profile(ADA)));
        long detailsBefore = engine.getHistoryService()
                .createHistoricDetailQuery()
                .variableUpdates()
                .processInstanceId(instance.getId())
                .count();

        for (int i = 0; i < 3; i++) {
            ObjectValue value = engine.getRuntimeService().getVariableTyped(instance.getId(), "applicantProfile");
            assertThat(value.getValue(Applicant.class)).isEqualTo(ADA);
        }
        engine.getRuntimeService().getVariables(instance.getId());

        assertThat(engine.getHistoryService()
                        .createHistoricDetailQuery()
                        .variableUpdates()
                        .processInstanceId(instance.getId())
                        .count())
                .isEqualTo(detailsBefore);
    }
}

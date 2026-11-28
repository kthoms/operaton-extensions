/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.variable.Variables;

/** Several engines, one after the other, on the same database: rotation, wrong keys, migration, key sources. */
class KeyRotationAndMigrationTest {

    final String database = "ve" + UUID.randomUUID().toString().replace("-", "");
    final List<ProcessEngine> engines = new ArrayList<>();
    final AtomicInteger counter = new AtomicInteger();

    @TempDir
    Path dir;

    private ProcessEngine engine(VariableEncryptionPlugin plugin) {
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName(database + "-" + counter.incrementAndGet());
        cfg.setJdbcUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setJobExecutorActivate(false);
        cfg.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (plugin != null) {
            cfg.getProcessEnginePlugins().add(plugin);
        }
        ProcessEngine engine = cfg.buildProcessEngine();
        engines.add(engine);
        if (engine.getRepositoryService()
                        .createProcessDefinitionQuery()
                        .processDefinitionKey("applicant-process")
                        .count()
                == 0) {
            engine.getRepositoryService()
                    .createDeployment()
                    .addClasspathResource("processes/applicant.bpmn20.xml")
                    .deploy();
        }
        return engine;
    }

    private static VariableEncryptionPlugin plugin(String active, Map<String, String> keys) {
        VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
        plugin.setKeys(keys);
        plugin.setActiveKeyId(active);
        plugin.setVariableNames(List.of("iban", "bic"));
        return plugin;
    }

    private RawRows raw(ProcessEngine engine) {
        return new RawRows(((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration()).getDataSource());
    }

    private void closeEngines() {
        for (ProcessEngine engine : engines) {
            engine.close();
        }
        engines.clear();
    }

    @AfterEach
    void tearDown() {
        if (!engines.isEmpty()) {
            RawRows rows = raw(engines.get(0));
            rows.update("drop all objects");
        }
        closeEngines();
    }

    @Test
    void newValuesUseTheActiveKeyAndOldValuesStayReadableUntilTheirKeyIsRemoved() {
        ProcessEngine first = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        String instanceA = first.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables().putValue("iban", "IBAN-A").putValue("bic", "BIC-A"))
                .getId();
        assertThat(raw(first).runtimeVariable("iban").get("TEXT_")).isEqualTo("$operaton-ve$1$k1");
        first.close();
        engines.clear();

        // rotation: k2 becomes active, k1 stays available for what it encrypted
        ProcessEngine second = engine(plugin("k2", Map.of("k1", TestKeys.KEY_1, "k2", TestKeys.KEY_2)));
        assertThat(second.getRuntimeService().getVariable(instanceA, "iban")).isEqualTo("IBAN-A");
        String instanceB = second.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-B"))
                .getId();
        second.getRuntimeService().setVariable(instanceA, "bic", "BIC-A2");

        RawRows rows = raw(second);
        assertThat(rows.query("select TEXT_ from ACT_RU_VARIABLE where NAME_ = 'iban' order by TEXT_"))
                .extracting(row -> row.get("TEXT_"))
                .containsExactly("$operaton-ve$1$k1", "$operaton-ve$1$k2"); // A's iban still k1, B's iban k2
        assertThat(rows.runtimeVariable("bic").get("TEXT_")).isEqualTo("$operaton-ve$1$k2");
        assertThat(rows.everythingStored()).doesNotContain("IBAN-A", "IBAN-B", "BIC-A");
        assertThat(second.getRuntimeService().getVariable(instanceA, "bic")).isEqualTo("BIC-A2");
        assertThat(second.getRuntimeService().getVariable(instanceB, "iban")).isEqualTo("IBAN-B");
        second.close();
        engines.clear();

        // k1 is retired too early: what it encrypted fails loudly, what k2 encrypted is fine
        ProcessEngine third = engine(plugin("k2", Map.of("k2", TestKeys.KEY_2)));
        assertThat(third.getRuntimeService().getVariable(instanceB, "iban")).isEqualTo("IBAN-B");
        assertThat(third.getRuntimeService().getVariable(instanceA, "bic")).isEqualTo("BIC-A2");
        assertThatThrownBy(() -> third.getRuntimeService().getVariable(instanceA, "iban"))
                .isInstanceOf(UnknownKeyException.class)
                .hasMessageContaining("'iban'")
                .hasMessageContaining("'k1'");
    }

    @Test
    void historyWrittenWithAnOldKeyIsStillReadableAfterRotation() {
        ProcessEngine first = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        String instance = first.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-H"))
                .getId();
        String taskId = first.getTaskService()
                .createTaskQuery()
                .processInstanceId(instance)
                .singleResult()
                .getId();
        first.getTaskService().complete(taskId);
        first.close();
        engines.clear();

        ProcessEngine second = engine(plugin("k2", Map.of("k1", TestKeys.KEY_1, "k2", TestKeys.KEY_2)));

        assertThat(second.getHistoryService()
                        .createHistoricVariableInstanceQuery()
                        .variableName("iban")
                        .singleResult()
                        .getValue())
                .isEqualTo("IBAN-H");
        assertThat(raw(second).historicVariable("iban").get("TEXT_")).isEqualTo("$operaton-ve$1$k1");
    }

    @Test
    void aWrongKeyUnderTheSameIdFailsLoudly() {
        ProcessEngine first = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        String instance = first.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-W"))
                .getId();
        first.close();
        engines.clear();

        ProcessEngine second = engine(plugin("k1", Map.of("k1", TestKeys.KEY_2)));

        assertThatThrownBy(() -> second.getRuntimeService().getVariable(instance, "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Integrity check failed")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("IBAN-W"));
    }

    @Test
    void enablingThePluginOnExistingPlaintextDataNeedsAnExplicitMigrationStep() {
        ProcessEngine plain = engine(null);
        String instance = plain.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process",
                        Variables.createVariables().putValue("iban", "IBAN-M").putValue("note", "n"))
                .getId();
        assertThat(raw(plain).runtimeVariable("iban").get("TEXT_")).isEqualTo("IBAN-M");
        plain.close();
        engines.clear();

        ProcessEngine strict = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        assertThatThrownBy(() -> strict.getRuntimeService().getVariable(instance, "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("stored in plaintext");
        assertThat(strict.getRuntimeService().getVariable(instance, "note")).isEqualTo("n");
        strict.close();
        engines.clear();

        VariableEncryptionPlugin migrating = plugin("k1", Map.of("k1", TestKeys.KEY_1));
        migrating.setAllowPlaintextRead(true);
        ProcessEngine migration = engine(migrating);
        Object value = migration.getRuntimeService().getVariable(instance, "iban");
        assertThat(value).isEqualTo("IBAN-M");
        migration.getRuntimeService().setVariable(instance, "iban", value); // the migration step: re-set
        assertThat(raw(migration).runtimeVariable("iban").get("TEXT_")).isEqualTo("$operaton-ve$1$k1");
        migration.close();
        engines.clear();

        ProcessEngine strictAgain = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        assertThat(strictAgain.getRuntimeService().getVariable(instance, "iban"))
                .isEqualTo("IBAN-M");
    }

    @Test
    void anEngineWithoutThePluginDoesNotReadCiphertextAsData() {
        ProcessEngine encrypted = engine(plugin("k1", Map.of("k1", TestKeys.KEY_1)));
        String instance = encrypted
                .getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-N"))
                .getId();
        encrypted.close();
        engines.clear();

        ProcessEngine without = engine(null);

        // the stored value is the marker text; the secret is not recoverable without the plugin and the key
        Object seen = without.getRuntimeService().getVariable(instance, "iban");
        assertThat(seen).isEqualTo("$operaton-ve$1$k1");
    }

    @Test
    void keysFromAKeyStoreFileAreUsedAndTheActiveAliasIsHonoured() throws Exception {
        SecretKey one = new SecretKeySpec(Base64.getDecoder().decode(TestKeys.KEY_1), "AES");
        SecretKey two = new SecretKeySpec(Base64.getDecoder().decode(TestKeys.KEY_2), "AES");
        char[] password = "changeit".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setEntry("key-1", new KeyStore.SecretKeyEntry(one), new KeyStore.PasswordProtection(password));
        store.setEntry("key-2", new KeyStore.SecretKeyEntry(two), new KeyStore.PasswordProtection(password));
        Path file = dir.resolve("keys.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password);
        }
        VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
        plugin.setKeyStore(file.toString());
        plugin.setKeyStorePassword("changeit");
        plugin.setActiveKeyId("key-2");
        plugin.setVariableNames(List.of("iban"));

        ProcessEngine engine = engine(plugin);
        String instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-S"))
                .getId();

        assertThat(raw(engine).runtimeVariable("iban").get("TEXT_")).isEqualTo("$operaton-ve$1$key-2");
        assertThat(engine.getRuntimeService().getVariable(instance, "iban")).isEqualTo("IBAN-S");
    }

    @Test
    void aCustomKeyProviderIsCalledForEveryEncryptionAndDecryption() {
        AtomicInteger lookups = new AtomicInteger();
        StaticKeyProvider delegate = new StaticKeyProvider("kms-1", Map.of("kms-1", TestKeys.KEY_3));
        KeyProvider counting = new KeyProvider() {
            @Override
            public String activeKeyId() {
                return delegate.activeKeyId();
            }

            @Override
            public SecretKey key(String keyId) {
                lookups.incrementAndGet();
                return delegate.key(keyId);
            }
        };
        VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
        plugin.setKeyProvider(counting);
        plugin.setVariableNames(List.of("iban"));

        ProcessEngine engine = engine(plugin);
        String instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "applicant-process", Variables.createVariables().putValue("iban", "IBAN-K"))
                .getId();
        int afterWrite = lookups.get();
        assertThat(afterWrite).isGreaterThan(0);
        assertThat(engine.getRuntimeService().getVariable(instance, "iban")).isEqualTo("IBAN-K");

        assertThat(lookups.get()).isGreaterThan(afterWrite);
        assertThat(raw(engine).runtimeVariable("iban").get("TEXT_")).isEqualTo("$operaton-ve$1$kms-1");
    }

    @Test
    void aKeyProviderThatFailsFailsTheWrite() {
        KeyProvider broken = new KeyProvider() {
            @Override
            public String activeKeyId() {
                return "gone";
            }

            @Override
            public SecretKey key(String keyId) {
                throw new UnknownKeyException(keyId);
            }
        };
        VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
        plugin.setKeyProvider(broken);
        plugin.setVariableNames(List.of("iban"));
        ProcessEngine engine = engine(plugin);

        assertThatThrownBy(() -> engine.getRuntimeService()
                        .startProcessInstanceByKey(
                                "applicant-process", Variables.createVariables().putValue("iban", "IBAN-X")))
                .isInstanceOf(UnknownKeyException.class);
        // the failed command wrote nothing, in particular no plaintext
        assertThat(raw(engine).everythingStored()).doesNotContain("IBAN-X");
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count())
                .isZero();
    }
}

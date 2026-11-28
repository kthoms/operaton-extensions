/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.spring;

import org.operaton.extensions.plugin.variableencryption.KeyProvider;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionPlugin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link VariableEncryptionPlugin} as a process engine plugin bean (the Operaton Spring
 * Boot starter picks up every {@code ProcessEnginePlugin} bean). A {@link KeyProvider} bean, if the
 * application defines one (for a KMS), replaces the configured keys. Invalid configuration fails the
 * application context at startup. Mapping only; no logic.
 */
@AutoConfiguration
@ConditionalOnClass(VariableEncryptionPlugin.class)
@ConditionalOnProperty(
        prefix = "operaton.extensions.plugin.variable-encryption",
        name = "enabled",
        matchIfMissing = true)
@EnableConfigurationProperties(VariableEncryptionProperties.class)
public class VariableEncryptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VariableEncryptionPlugin variableEncryptionPlugin(
            VariableEncryptionProperties properties, ObjectProvider<KeyProvider> keyProvider) {
        VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
        KeyProvider provided = keyProvider.getIfUnique();
        if (provided != null) {
            plugin.setKeyProvider(provided);
        } else {
            plugin.setKeys(properties.getKeys());
            plugin.setActiveKeyId(properties.getActiveKeyId());
            plugin.setKeyStore(properties.getKeyStore().getPath());
            plugin.setKeyStoreType(properties.getKeyStore().getType());
            plugin.setKeyStorePassword(properties.getKeyStore().getPassword());
        }
        plugin.setVariableNames(properties.getVariableNames());
        plugin.setEncryptMarkedValues(properties.isEncryptMarkedValues());
        plugin.setAllowPlaintextRead(properties.isAllowPlaintextRead());
        plugin.validate();
        return plugin;
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.spring;

import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link HistoryOpenSearchPlugin} as a process engine plugin bean (the Operaton Spring
 * Boot starter picks up every {@code ProcessEnginePlugin} bean). Invalid configuration fails the
 * application context at startup. Mapping only; no logic.
 */
@AutoConfiguration
@ConditionalOnClass(HistoryOpenSearchPlugin.class)
@ConditionalOnProperty(
        prefix = "operaton.extensions.plugin.history-opensearch",
        name = "enabled",
        matchIfMissing = true)
@EnableConfigurationProperties(HistoryOpenSearchProperties.class)
public class HistoryOpenSearchAutoConfiguration {

    /** The plugin bean, configured from the properties; closed with the application context. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public HistoryOpenSearchPlugin historyOpenSearchPlugin(HistoryOpenSearchProperties properties) {
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        plugin.setUris(properties.getUris());
        plugin.setUsername(properties.getUsername());
        plugin.setPassword(properties.getPassword());
        plugin.setApiKey(properties.getApiKey());
        HistoryOpenSearchProperties.Tls tls = properties.getTls();
        plugin.setTrustStore(tls.getTrustStore());
        plugin.setTrustStorePassword(tls.getTrustStorePassword());
        plugin.setTrustStoreType(tls.getTrustStoreType());
        plugin.setKeyStore(tls.getKeyStore());
        plugin.setKeyStorePassword(tls.getKeyStorePassword());
        plugin.setKeyStoreType(tls.getKeyStoreType());
        plugin.setInsecureSkipVerify(tls.isInsecureSkipVerify());
        plugin.setConnectTimeoutMillis(properties.getConnectTimeout().toMillis());
        plugin.setRequestTimeoutMillis(properties.getRequestTimeout().toMillis());
        plugin.setIndexPrefix(properties.getIndexPrefix());
        plugin.setIndexDatePattern(properties.getIndexDatePattern());
        plugin.setNumberOfShards(properties.getNumberOfShards());
        plugin.setNumberOfReplicas(properties.getNumberOfReplicas());
        plugin.setManageTemplates(properties.isManageTemplates());
        plugin.setEventTypes(properties.getEventTypes());
        plugin.setFailurePolicy(properties.getFailurePolicy());
        plugin.setSendMode(properties.getSendMode());
        plugin.setBatchSize(properties.getBatchSize());
        plugin.setFlushIntervalMillis(properties.getFlushInterval().toMillis());
        plugin.setQueueCapacity(properties.getQueueCapacity());
        plugin.setMaxRetries(properties.getMaxRetries());
        plugin.setInitialBackoffMillis(properties.getInitialBackoff().toMillis());
        plugin.setMaxBackoffMillis(properties.getMaxBackoff().toMillis());
        plugin.setSendTimeoutMillis(properties.getSendTimeout().toMillis());
        plugin.setKeepDatabaseHandler(properties.isKeepDatabaseHandler());
        plugin.setIncludeVariableValues(properties.isIncludeVariableValues());
        plugin.setMaxVariableValueLength(properties.getMaxVariableValueLength());
        plugin.setLookupCacheSize(properties.getLookupCacheSize());
        plugin.validate();
        return plugin;
    }
}

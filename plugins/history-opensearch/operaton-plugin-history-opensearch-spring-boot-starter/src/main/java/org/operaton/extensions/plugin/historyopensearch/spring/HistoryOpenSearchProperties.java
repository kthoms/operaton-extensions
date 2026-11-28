/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.operaton.extensions.plugin.historyopensearch.FailurePolicy;
import org.operaton.extensions.plugin.historyopensearch.SendMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** Properties {@code operaton.extensions.plugin.history-opensearch.*}; mapped one to one onto the plugin. */
@ConfigurationProperties("operaton.extensions.plugin.history-opensearch")
public class HistoryOpenSearchProperties {

    /** Whether the plugin is registered. */
    private boolean enabled = true;
    /** OpenSearch endpoints, tried round-robin. */
    private List<String> uris = new ArrayList<>(List.of("http://localhost:9200"));
    /** User name for basic authentication. */
    private String username;
    /** Password for basic authentication. */
    private String password;
    /** API key (base64 of id:secret); excludes basic authentication. */
    private String apiKey;
    /** TLS settings. */
    @NestedConfigurationProperty
    private final Tls tls = new Tls();
    /** Connection timeout. */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** Timeout of one HTTP request. */
    private Duration requestTimeout = Duration.ofSeconds(30);
    /** First part of the index names. */
    private String indexPrefix = "operaton";
    /** DateTimeFormatter pattern (UTC) of the date part of the index names; empty for none. */
    private String indexDatePattern = "yyyy.MM";
    /** Primary shards of new indices; unset: cluster default. */
    private Integer numberOfShards;
    /** Replicas of new indices; unset: cluster default. */
    private Integer numberOfReplicas;
    /** Install and update the index templates at startup. */
    private boolean manageTemplates = true;
    /** Categories or category.event to index; empty: all. */
    private List<String> eventTypes = new ArrayList<>();
    /** Reaction to documents that cannot be written. */
    private FailurePolicy failurePolicy = FailurePolicy.FAIL;
    /** Background queue or synchronous write. */
    private SendMode sendMode = SendMode.ASYNC;
    /** Documents per bulk request. */
    private int batchSize = 500;
    /** Longest wait of a queued document before its bulk request. */
    private Duration flushInterval = Duration.ofSeconds(1);
    /** Documents the queue holds before committing threads wait. */
    private int queueCapacity = 10_000;
    /** Retries of a failed request or a rejected document. */
    private int maxRetries = 3;
    /** Wait before the first retry; doubles per retry. */
    private Duration initialBackoff = Duration.ofMillis(200);
    /** Upper limit of the wait between retries. */
    private Duration maxBackoff = Duration.ofSeconds(5);
    /** Synchronous mode: wait per transaction; background mode: wait for a free queue slot and for the queue to drain on shutdown. */
    private Duration sendTimeout = Duration.ofSeconds(30);
    /** Keep the database history handler. */
    private boolean keepDatabaseHandler = true;
    /** Index variable values in variable documents. */
    private boolean includeVariableValues = false;
    /** Longest text value indexed; longer values are cut and flagged. */
    private int maxVariableValueLength = 4096;
    /** How many document-to-index assignments are remembered. */
    private int lookupCacheSize = 100_000;

    /** TLS settings of the connection. */
    public static class Tls {
        /** Trust store with the CA of the server certificate; default: the JVM's. */
        private String trustStore;
        /** Password of the trust store. */
        private String trustStorePassword;
        /** Type of the trust store. */
        private String trustStoreType;
        /** Key store with the client certificate (mutual TLS). */
        private String keyStore;
        /** Password of the key store. */
        private String keyStorePassword;
        /** Type of the key store. */
        private String keyStoreType;
        /** Skip verification of the server certificate and host name (development only). */
        private boolean insecureSkipVerify;

        public String getTrustStore() {
            return trustStore;
        }

        public void setTrustStore(String trustStore) {
            this.trustStore = trustStore;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }

        public String getTrustStoreType() {
            return trustStoreType;
        }

        public void setTrustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
        }

        public String getKeyStore() {
            return keyStore;
        }

        public void setKeyStore(String keyStore) {
            this.keyStore = keyStore;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public String getKeyStoreType() {
            return keyStoreType;
        }

        public void setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
        }

        public boolean isInsecureSkipVerify() {
            return insecureSkipVerify;
        }

        public void setInsecureSkipVerify(boolean insecureSkipVerify) {
            this.insecureSkipVerify = insecureSkipVerify;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getUris() {
        return uris;
    }

    public void setUris(List<String> uris) {
        this.uris = uris;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Tls getTls() {
        return tls;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    public String getIndexDatePattern() {
        return indexDatePattern;
    }

    public void setIndexDatePattern(String indexDatePattern) {
        this.indexDatePattern = indexDatePattern;
    }

    public Integer getNumberOfShards() {
        return numberOfShards;
    }

    public void setNumberOfShards(Integer numberOfShards) {
        this.numberOfShards = numberOfShards;
    }

    public Integer getNumberOfReplicas() {
        return numberOfReplicas;
    }

    public void setNumberOfReplicas(Integer numberOfReplicas) {
        this.numberOfReplicas = numberOfReplicas;
    }

    public boolean isManageTemplates() {
        return manageTemplates;
    }

    public void setManageTemplates(boolean manageTemplates) {
        this.manageTemplates = manageTemplates;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public SendMode getSendMode() {
        return sendMode;
    }

    public void setSendMode(SendMode sendMode) {
        this.sendMode = sendMode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public boolean isKeepDatabaseHandler() {
        return keepDatabaseHandler;
    }

    public void setKeepDatabaseHandler(boolean keepDatabaseHandler) {
        this.keepDatabaseHandler = keepDatabaseHandler;
    }

    public boolean isIncludeVariableValues() {
        return includeVariableValues;
    }

    public void setIncludeVariableValues(boolean includeVariableValues) {
        this.includeVariableValues = includeVariableValues;
    }

    public int getMaxVariableValueLength() {
        return maxVariableValueLength;
    }

    public void setMaxVariableValueLength(int maxVariableValueLength) {
        this.maxVariableValueLength = maxVariableValueLength;
    }

    public int getLookupCacheSize() {
        return lookupCacheSize;
    }

    public void setLookupCacheSize(int lookupCacheSize) {
        this.lookupCacheSize = lookupCacheSize;
    }
}

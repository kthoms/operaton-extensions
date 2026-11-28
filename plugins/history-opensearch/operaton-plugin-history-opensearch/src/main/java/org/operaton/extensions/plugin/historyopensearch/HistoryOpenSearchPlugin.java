/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.extensions.plugin.historyopensearch.internal.CloseOnEngineShutdownInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that indexes Operaton history into OpenSearch after the engine transaction
 * commits (see {@link OpenSearchHistoryEventHandler} for the document model and the delivery semantics).
 *
 * <p>Register it as a {@code processEnginePlugin} of the engine configuration. By default the database
 * history handler stays active next to OpenSearch; {@link #setKeepDatabaseHandler(boolean)
 * keepDatabaseHandler=false} replaces it (the history tables stay empty then).
 *
 * <p>One plugin instance serves one engine. The writer is created in {@code preInit}; the index
 * templates are installed once the engine is built. The writer is closed (queued documents are written
 * first) when the engine is closed or {@link #close()} is called. Configuration errors fail engine startup
 * with an {@link IllegalArgumentException} or {@link HistoryOpenSearchException}.
 *
 * <p>Thread-safety: configure before the engine is built; after that the instance is safe for concurrent
 * use by the engine's threads.
 */
public class HistoryOpenSearchPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryOpenSearchPlugin.class);

    private List<String> uris = List.of("http://localhost:9200");
    private String username;
    private String password;
    private String apiKey;
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType;
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType;
    private boolean insecureSkipVerify;
    private long connectTimeoutMillis = 5_000;
    private long requestTimeoutMillis = 30_000;
    private String indexPrefix = "operaton";
    private String indexDatePattern = "yyyy.MM";
    private Integer numberOfShards;
    private Integer numberOfReplicas;
    private boolean manageTemplates = true;
    private Collection<String> eventTypes = Set.of();
    private FailurePolicy failurePolicy = FailurePolicy.FAIL;
    private SendMode sendMode = SendMode.ASYNC;
    private int batchSize = 500;
    private long flushIntervalMillis = 1_000;
    private int queueCapacity = 10_000;
    private int maxRetries = 3;
    private long initialBackoffMillis = 200;
    private long maxBackoffMillis = 5_000;
    private long sendTimeoutMillis = 30_000;
    private boolean keepDatabaseHandler = true;
    private boolean includeVariableValues;
    private int maxVariableValueLength = 4_096;
    private int lookupCacheSize = 100_000;

    private volatile OpenSearchHistoryEventHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** OpenSearch endpoints, {@code http://host:9200} or {@code https://host:9200}; default {@code http://localhost:9200}. */
    public void setUris(Collection<String> uris) {
        this.uris = uris == null ? List.of() : List.copyOf(uris);
    }

    /** User name for basic authentication; needs {@link #setPassword(String)}. */
    public void setUsername(String username) {
        this.username = username;
    }

    /** Password for basic authentication. Never logged. */
    public void setPassword(String password) {
        this.password = password;
    }

    /** API key (base64 of {@code id:secret}), sent as {@code Authorization: ApiKey ...}; excludes basic authentication. Never logged. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Trust store with the CA of the server certificate (default: the JVM's). */
    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    /** Password of the trust store. Never logged. */
    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    /** Type of the trust store, default {@code PKCS12}. */
    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    /** Key store with the client certificate, for mutual TLS. */
    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    /** Password of the key store and its key. Never logged. */
    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    /** Type of the key store, default {@code PKCS12}. */
    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    /** Skip verification of the server certificate and host name. Development clusters only; logs a warning. */
    public void setInsecureSkipVerify(boolean insecureSkipVerify) {
        this.insecureSkipVerify = insecureSkipVerify;
    }

    /** Connection timeout in milliseconds, default 5000. */
    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /** Timeout of one HTTP request in milliseconds, default 30000. */
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /** First part of the index names, default {@code operaton} ({@code operaton-process-instances-2026.11}). */
    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    /**
     * {@link java.time.format.DateTimeFormatter} pattern (UTC) of the date part of the index names,
     * default {@code yyyy.MM} (monthly). Empty: one index per kind, no date part.
     */
    public void setIndexDatePattern(String indexDatePattern) {
        this.indexDatePattern = indexDatePattern;
    }

    /** Primary shards of new indices; unset: the cluster's default. */
    public void setNumberOfShards(Integer numberOfShards) {
        this.numberOfShards = numberOfShards;
    }

    /** Replicas of new indices; unset: the cluster's default. */
    public void setNumberOfReplicas(Integer numberOfReplicas) {
        this.numberOfReplicas = numberOfReplicas;
    }

    /** Install and update the index templates at startup (default {@code true}); turn off when they are managed elsewhere. */
    public void setManageTemplates(boolean manageTemplates) {
        this.manageTemplates = manageTemplates;
    }

    /**
     * Categories or category and event names to index, e.g. {@code process-instance}, {@code
     * task-instance.complete}. Categories: {@code process-instance}, {@code activity-instance}, {@code
     * task-instance}, {@code variable-instance}, {@code incident}. Empty: all of them.
     */
    public void setEventTypes(Collection<String> eventTypes) {
        this.eventTypes = eventTypes == null ? Set.of() : List.copyOf(eventTypes);
    }

    /** Reaction to documents that cannot be written, default {@link FailurePolicy#FAIL}. */
    public void setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    /** Background queue ({@link SendMode#ASYNC}, default) or write before the command returns ({@link SendMode#SYNC}). */
    public void setSendMode(SendMode sendMode) {
        this.sendMode = sendMode;
    }

    /** Documents per bulk request, default 500. */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /** Longest wait of a queued document before its bulk request, in milliseconds, default 1000. */
    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }

    /** Documents the queue holds before committing threads wait, default 10000. */
    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    /** Retries of a failed request or a rejected document, default 3. */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /** Wait before the first retry in milliseconds (doubles per retry), default 200. */
    public void setInitialBackoffMillis(long initialBackoffMillis) {
        this.initialBackoffMillis = initialBackoffMillis;
    }

    /** Upper limit of the wait between retries in milliseconds, default 5000. */
    public void setMaxBackoffMillis(long maxBackoffMillis) {
        this.maxBackoffMillis = maxBackoffMillis;
    }

    /**
     * Longest wait for the documents of one transaction (synchronous mode), for a free queue slot
     * (background mode) and for the queue to drain on shutdown, in milliseconds, default 30000.
     */
    public void setSendTimeoutMillis(long sendTimeoutMillis) {
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    /** Keep the engine's database history handler next to OpenSearch (default {@code true}). */
    public void setKeepDatabaseHandler(boolean keepDatabaseHandler) {
        this.keepDatabaseHandler = keepDatabaseHandler;
    }

    /** Index variable values in variable documents (default {@code false}: they may carry personal data). */
    public void setIncludeVariableValues(boolean includeVariableValues) {
        this.includeVariableValues = includeVariableValues;
    }

    /** Longest text value indexed; longer values are cut and flagged {@code valueTruncated}. Default 4096. */
    public void setMaxVariableValueLength(int maxVariableValueLength) {
        this.maxVariableValueLength = maxVariableValueLength;
    }

    /** How many document-to-index assignments are remembered, default 100000. */
    public void setLookupCacheSize(int lookupCacheSize) {
        this.lookupCacheSize = lookupCacheSize;
    }

    /** Validates the configuration; called by {@code preInit} and usable for fail-fast startup checks. */
    public HistoryOpenSearchOptions validate() {
        List<URI> parsed = new ArrayList<>();
        for (String uri : uris) {
            try {
                parsed.add(URI.create(uri.trim()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("history-opensearch: invalid OpenSearch URI '" + uri + "'", e);
            }
        }
        OpenSearchConnection connection = new OpenSearchConnection(
                parsed,
                username,
                password,
                apiKey,
                new TlsSettings(
                        trustStore,
                        trustStorePassword,
                        trustStoreType,
                        keyStore,
                        keyStorePassword,
                        keyStoreType,
                        insecureSkipVerify),
                Duration.ofMillis(connectTimeoutMillis),
                Duration.ofMillis(requestTimeoutMillis));
        return new HistoryOpenSearchOptions(
                connection,
                indexPrefix,
                indexDatePattern,
                numberOfShards,
                numberOfReplicas,
                manageTemplates,
                Set.copyOf(eventTypes),
                failurePolicy,
                sendMode,
                batchSize,
                Duration.ofMillis(flushIntervalMillis),
                queueCapacity,
                maxRetries,
                Duration.ofMillis(initialBackoffMillis),
                Duration.ofMillis(maxBackoffMillis),
                Duration.ofMillis(sendTimeoutMillis),
                includeVariableValues,
                maxVariableValueLength,
                lookupCacheSize);
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (handler != null) {
            throw new IllegalStateException(
                    "history-opensearch: this plugin instance already serves an engine; use one plugin per engine");
        }
        HistoryEventHandler existing = configuration.getHistoryEventHandler();
        if (existing != null) {
            throw new HistoryOpenSearchException(
                    "history-opensearch: the engine configuration already has a historyEventHandler ("
                            + existing.getClass().getName()
                            + "); the plugin extends customHistoryEventHandlers, which the engine"
                            + " ignores in that case. Remove the explicit handler or add the OpenSearch handler to it yourself.");
        }
        HistoryOpenSearchOptions options = validate();
        LOG.info("history-opensearch: indexing history into {}", options.connection());
        OpenSearchHistoryEventHandler created =
                new OpenSearchHistoryEventHandler(options, new ObjectMapper(), configuration.getProcessEngineName());
        handler = created;

        List<HistoryEventHandler> handlers = configuration.getCustomHistoryEventHandlers();
        List<HistoryEventHandler> updated = handlers == null ? new ArrayList<>() : new ArrayList<>(handlers);
        updated.add(created);
        configuration.setCustomHistoryEventHandlers(updated);
        configuration.setEnableDefaultDbHistoryEventHandler(keepDatabaseHandler);

        List<CommandInterceptor> interceptors = configuration.getCustomPreCommandInterceptorsTxRequired();
        List<CommandInterceptor> withClose = interceptors == null ? new ArrayList<>() : new ArrayList<>(interceptors);
        withClose.add(new CloseOnEngineShutdownInterceptor(this::close));
        configuration.setCustomPreCommandInterceptorsTxRequired(withClose);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        if (configuration.getHistoryLevel() != null
                && configuration.getHistoryLevel().getId() == ProcessEngineConfigurationImpl.HISTORYLEVEL_NONE) {
            LOG.warn("history-opensearch: the engine's history level is 'none', no history will be indexed");
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        getHandler().start();
    }

    /** The handler registered with the engine; available after {@code preInit}. */
    public OpenSearchHistoryEventHandler getHandler() {
        OpenSearchHistoryEventHandler current = handler;
        if (current == null) {
            throw new IllegalStateException("history-opensearch: the plugin has not been initialised by an engine yet");
        }
        return current;
    }

    /** Writes what is queued and stops the writer. Idempotent; called automatically when the engine closes. */
    @Override
    public void close() {
        OpenSearchHistoryEventHandler current = handler;
        if (current != null && closed.compareAndSet(false, true)) {
            LOG.info("history-opensearch: closing the writer");
            current.close();
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.plugin.historykafka.FailurePolicy;
import org.operaton.extensions.plugin.historykafka.PayloadFormat;
import org.operaton.extensions.plugin.historykafka.SendMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.plugin.history-kafka.*}; mapped one to one onto the plugin. */
@ConfigurationProperties("operaton.extensions.plugin.history-kafka")
public class HistoryKafkaProperties {

    /** Whether the plugin is registered. */
    private boolean enabled = true;
    /** Kafka bootstrap servers. Required. */
    private String bootstrapServers;
    /** One topic for all events; empty: one topic per category. */
    private String topic;
    /** Prefix of the per-category topics. */
    private String topicPrefix = "operaton.history.";
    /** Explicit topic per category or category.event. */
    private Map<String, String> topicMapping = new HashMap<>();
    /** Categories or category.event to publish; empty: all. */
    private List<String> eventTypes = new ArrayList<>();
    /** Wire format. */
    private PayloadFormat format = PayloadFormat.JSON;
    /** CloudEvents source; default urn:operaton:engine:NAME. */
    private String cloudEventsSource;
    /** Reaction to a failed hand-over after commit. */
    private FailurePolicy failurePolicy = FailurePolicy.FAIL;
    /** Asynchronous or synchronous send. */
    private SendMode sendMode = SendMode.ASYNC;
    /** Wait per transaction in sync mode and producer max.block.ms. */
    private Duration sendTimeout = Duration.ofSeconds(10);
    /** Keep the database history handler. */
    private boolean keepDatabaseHandler = true;
    /** Include variable values in variable events. */
    private boolean includeVariableValues = false;
    /** Kafka producer properties passed through. */
    private Map<String, String> producer = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public Map<String, String> getTopicMapping() {
        return topicMapping;
    }

    public void setTopicMapping(Map<String, String> topicMapping) {
        this.topicMapping = topicMapping;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public PayloadFormat getFormat() {
        return format;
    }

    public void setFormat(PayloadFormat format) {
        this.format = format;
    }

    public String getCloudEventsSource() {
        return cloudEventsSource;
    }

    public void setCloudEventsSource(String cloudEventsSource) {
        this.cloudEventsSource = cloudEventsSource;
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

    public Map<String, String> getProducer() {
        return producer;
    }

    public void setProducer(Map<String, String> producer) {
        this.producer = producer;
    }
}

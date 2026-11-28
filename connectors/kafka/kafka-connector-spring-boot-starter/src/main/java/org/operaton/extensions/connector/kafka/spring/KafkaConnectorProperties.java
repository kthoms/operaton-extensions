/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.connector.kafka.consumer.InboundAction;
import org.operaton.extensions.connector.kafka.consumer.InboundSubscription;
import org.operaton.extensions.connector.kafka.consumer.PoisonHandling;
import org.operaton.extensions.connector.kafka.consumer.ValueFormat;
import org.operaton.extensions.connector.kafka.producer.DeliveryFailureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.connector.kafka.*}; mapped onto the plugin, no logic. */
@ConfigurationProperties(prefix = "operaton.extensions.connector.kafka")
public class KafkaConnectorProperties {

    /** {@code bootstrap.servers}; the integration is only configured when set. */
    private String bootstrapServers;

    private final Producer producer = new Producer();
    private final Consumer consumer = new Consumer();

    /** Inbound subscriptions: records that start processes or correlate messages. */
    private List<Subscription> subscriptions = new ArrayList<>();

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public Producer getProducer() {
        return producer;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public List<Subscription> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<Subscription> subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** Outbound side. */
    public static class Producer {
        /** Further Kafka producer properties, passed through (for example {@code acks}, {@code security.protocol}). */
        private Map<String, String> properties = new LinkedHashMap<>();
        /** How long a send waits for the broker's acknowledgement. */
        private Duration sendTimeout = Duration.ofSeconds(30);
        /** What to do when an after-commit send fails: LOG or THROW. */
        private DeliveryFailureMode failureMode = DeliveryFailureMode.LOG;

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public DeliveryFailureMode getFailureMode() {
            return failureMode;
        }

        public void setFailureMode(DeliveryFailureMode failureMode) {
            this.failureMode = failureMode;
        }
    }

    /** Inbound side, common to all subscriptions. */
    public static class Consumer {
        /** Further Kafka consumer properties, passed through (for example {@code security.protocol}). */
        private Map<String, String> properties = new LinkedHashMap<>();
        /** How long stopping waits for the record in progress. */
        private Duration shutdownTimeout = Duration.ofSeconds(15);

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }
    }

    /** One inbound subscription; see {@link InboundSubscription}. */
    public static class Subscription {
        /** Unique name, used for the thread names, the default group and the default dead-letter topic. */
        private String name;
        /** Topics to consume. */
        private List<String> topics = new ArrayList<>();
        /** Consumer group; default {@code operaton-<name>}. */
        private String groupId;
        /** START_PROCESS or CORRELATE_MESSAGE. */
        private InboundAction action;
        /** Process definition key (START_PROCESS). */
        private String processDefinitionKey;
        /** Message name (CORRELATE_MESSAGE). */
        private String messageName;
        /** Tenant of the process definition or the message. */
        private String tenantId;
        /** Source of the business key, for example {@code $.orderId}. */
        private String businessKey;
        /** Variable name to source: the target instance must have equal variables (CORRELATE_MESSAGE). */
        private Map<String, String> correlationKeys = new LinkedHashMap<>();
        /** Variable name to source of the variables to set; default {@code payload: value}. */
        private Map<String, String> variables = new LinkedHashMap<>();
        /** JSON or TEXT. */
        private ValueFormat valueFormat = ValueFormat.JSON;
        /** Number of consumer threads. */
        private int concurrency = 1;
        /** DEAD_LETTER or SKIP. */
        private PoisonHandling onPoison = PoisonHandling.DEAD_LETTER;
        /** Dead-letter topic; default {@code <name>.dlt}. */
        private String deadLetterTopic;
        /** Attempts of the engine call before the record is poison; 0 = without limit. */
        private int maxAttempts = 0;
        /** Pause before the engine call of a failed record is retried. */
        private Duration retryBackoff = Duration.ofSeconds(1);
        /** Poll timeout. */
        private Duration pollTimeout = Duration.ofMillis(500);
        /** Kafka consumer properties of this subscription, overriding the common ones. */
        private Map<String, String> properties = new LinkedHashMap<>();

        InboundSubscription toSubscription() {
            return InboundSubscription.builder(name)
                    .topics(topics)
                    .groupId(groupId)
                    .action(action)
                    .processDefinitionKey(processDefinitionKey)
                    .messageName(messageName)
                    .tenantId(tenantId)
                    .businessKey(businessKey)
                    .correlationKeys(correlationKeys)
                    .variables(variables)
                    .valueFormat(valueFormat)
                    .concurrency(concurrency)
                    .onPoison(onPoison)
                    .deadLetterTopic(deadLetterTopic)
                    .maxAttempts(maxAttempts)
                    .retryBackoff(retryBackoff)
                    .pollTimeout(pollTimeout)
                    .consumerProperties(properties)
                    .build();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getTopics() {
            return topics;
        }

        public void setTopics(List<String> topics) {
            this.topics = topics;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public InboundAction getAction() {
            return action;
        }

        public void setAction(InboundAction action) {
            this.action = action;
        }

        public String getProcessDefinitionKey() {
            return processDefinitionKey;
        }

        public void setProcessDefinitionKey(String processDefinitionKey) {
            this.processDefinitionKey = processDefinitionKey;
        }

        public String getMessageName() {
            return messageName;
        }

        public void setMessageName(String messageName) {
            this.messageName = messageName;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public Map<String, String> getCorrelationKeys() {
            return correlationKeys;
        }

        public void setCorrelationKeys(Map<String, String> correlationKeys) {
            this.correlationKeys = correlationKeys;
        }

        public Map<String, String> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, String> variables) {
            this.variables = variables;
        }

        public ValueFormat getValueFormat() {
            return valueFormat;
        }

        public void setValueFormat(ValueFormat valueFormat) {
            this.valueFormat = valueFormat;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public PoisonHandling getOnPoison() {
            return onPoison;
        }

        public void setOnPoison(PoisonHandling onPoison) {
            this.onPoison = onPoison;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public Duration getPollTimeout() {
            return pollTimeout;
        }

        public void setPollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }
}

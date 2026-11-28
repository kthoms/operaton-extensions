/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InboundSubscriptionTest {

    @Test
    void defaults() {
        InboundSubscription s = InboundSubscription.builder("orders")
                .topics("order-placed")
                .startProcess("order-process")
                .build();

        assertThat(s.groupId()).isEqualTo("operaton-orders");
        assertThat(s.deadLetterTopic()).isEqualTo("orders.dlt");
        assertThat(s.variables()).containsOnlyKeys("payload");
        assertThat(s.maxAttempts()).isZero();
        assertThat(s.valueFormat()).isEqualTo(ValueFormat.JSON);
    }

    @Test
    void invalidConfigurationsFailLoudly() {
        assertThatThrownBy(
                        () -> InboundSubscription.builder("a").startProcess("p").build())
                .hasMessageContaining("at least one topic");
        assertThatThrownBy(() -> InboundSubscription.builder("a").topics("t").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .startProcess(null)
                        .build())
                .hasMessageContaining("requires processDefinitionKey");
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .startProcess("p")
                        .correlationKey("x", "key")
                        .build())
                .hasMessageContaining("does not take correlation keys");
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .correlateMessage("m")
                        .variable("v", "$.bad[")
                        .build())
                .hasMessageContaining("variable 'v'");
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .startProcess("p")
                        .valueFormat(ValueFormat.TEXT)
                        .businessKey("$.id")
                        .build())
                .hasMessageContaining("needs JSON");
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .startProcess("p")
                        .consumerProperty("enable.auto.commit", "true")
                        .build())
                .hasMessageContaining("cannot be overridden");
        assertThatThrownBy(() -> InboundSubscription.builder("a")
                        .topics("t")
                        .startProcess("p")
                        .concurrency(0)
                        .build())
                .hasMessageContaining("concurrency");
    }
}

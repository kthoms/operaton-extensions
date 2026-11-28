/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

/**
 * Where the broker stored a record.
 *
 * @param topic the topic
 * @param partition the partition
 * @param offset the offset, {@code -1} when the broker did not report one
 * @param timestamp the record timestamp (epoch milliseconds)
 */
public record SendResult(String topic, int partition, long offset, long timestamp) {}

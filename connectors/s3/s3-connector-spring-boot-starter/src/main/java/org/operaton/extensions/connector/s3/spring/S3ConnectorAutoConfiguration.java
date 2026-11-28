/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.spring;

import org.operaton.extensions.connector.s3.S3ConnectorPlugin;
import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.delegate.S3CopyDelegate;
import org.operaton.extensions.connector.s3.delegate.S3DeleteDelegate;
import org.operaton.extensions.connector.s3.delegate.S3GetDelegate;
import org.operaton.extensions.connector.s3.delegate.S3ListDelegate;
import org.operaton.extensions.connector.s3.delegate.S3PresignDelegate;
import org.operaton.extensions.connector.s3.delegate.S3PutDelegate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the S3 stores from {@code operaton.extensions.connector.s3.*}, the engine plugin that
 * hands them to the connectors, and the delegate beans {@code s3PutDelegate}, {@code
 * s3GetDelegate}, {@code s3ListDelegate}, {@code s3CopyDelegate}, {@code s3DeleteDelegate} and
 * {@code s3PresignDelegate}. Active as soon as at least one store is configured; invalid
 * configuration stops the application at startup.
 */
@AutoConfiguration
@ConditionalOnClass(S3ConnectorPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.connector.s3", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(S3ConnectorProperties.class)
public class S3ConnectorAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public S3StoreRegistry s3StoreRegistry(S3ConnectorProperties properties) {
        return new S3StoreRegistry(properties.getStores(), properties.getDefaultStore());
    }

    @Bean
    @ConditionalOnMissingBean
    public S3ConnectorPlugin s3ConnectorPlugin(S3StoreRegistry registry) {
        return new S3ConnectorPlugin(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3PutDelegate s3PutDelegate(S3StoreRegistry registry) {
        return new S3PutDelegate(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3GetDelegate s3GetDelegate(S3StoreRegistry registry) {
        return new S3GetDelegate(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3ListDelegate s3ListDelegate(S3StoreRegistry registry) {
        return new S3ListDelegate(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3CopyDelegate s3CopyDelegate(S3StoreRegistry registry) {
        return new S3CopyDelegate(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3DeleteDelegate s3DeleteDelegate(S3StoreRegistry registry) {
        return new S3DeleteDelegate(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public S3PresignDelegate s3PresignDelegate(S3StoreRegistry registry) {
        return new S3PresignDelegate(registry);
    }
}

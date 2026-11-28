/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.s3.S3ConnectorPlugin;
import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.delegate.S3PutDelegate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class S3ConnectorAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(S3ConnectorAutoConfiguration.class));

    @Test
    void bindsStoresAndRegistersPluginAndDelegates() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.s3.default-store=docs",
                        "operaton.extensions.connector.s3.stores.docs.endpoint=http://localhost:9000",
                        "operaton.extensions.connector.s3.stores.docs.region=eu-west-1",
                        "operaton.extensions.connector.s3.stores.docs.bucket=documents",
                        "operaton.extensions.connector.s3.stores.docs.access-key=a",
                        "operaton.extensions.connector.s3.stores.docs.secret-key=b",
                        "operaton.extensions.connector.s3.stores.docs.max-download-bytes=2048",
                        "operaton.extensions.connector.s3.stores.docs.presign-expiry=PT1H",
                        "operaton.extensions.connector.s3.stores.archive.bucket=archive",
                        "operaton.extensions.connector.s3.stores.archive.region=eu-west-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(S3ConnectorPlugin.class).hasSingleBean(S3PutDelegate.class);
                    S3StoreRegistry registry = context.getBean(S3StoreRegistry.class);
                    assertThat(registry.store(null).config().getBucket()).isEqualTo("documents");
                    assertThat(registry.store(null).config().getMaxDownloadBytes())
                            .isEqualTo(2048);
                    assertThat(registry.store("docs").config().getPresignExpiry())
                            .isEqualTo(Duration.ofHours(1));
                    assertThat(registry.store("archive").config().getBucket()).isEqualTo("archive");
                });
    }

    @Test
    void refusesToStartWithoutStores() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("No S3 store configured"));
    }

    @Test
    void refusesToStartWithInvalidStore() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.s3.stores.docs.access-key=only-half",
                        "operaton.extensions.connector.s3.stores.docs.region=eu-west-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("access-key and secret-key must be set together"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("operaton.extensions.connector.s3.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(S3ConnectorPlugin.class));
    }
}

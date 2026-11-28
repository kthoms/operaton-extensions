/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

/** Store configurations for {@code operaton.cfg.xml}, built from the system properties of the test. */
public final class TestStores {

    private TestStores() {}

    /** The default store; its download limit is small so tests can exceed it. */
    public static S3StoreConfig docs() {
        S3StoreConfig config = store(System.getProperty("s3.test.bucket"));
        config.setMaxDownloadBytes(1024);
        return config;
    }

    public static S3StoreConfig other() {
        return store(System.getProperty("s3.test.bucket2"));
    }

    private static S3StoreConfig store(String bucket) {
        S3StoreConfig config = new S3StoreConfig();
        config.setEndpoint(System.getProperty("s3.test.endpoint"));
        config.setRegion("us-east-1");
        config.setBucket(bucket);
        config.setAccessKey(System.getProperty("s3.test.accessKey"));
        config.setSecretKey(System.getProperty("s3.test.secretKey"));
        return config;
    }
}

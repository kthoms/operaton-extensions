/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.net.URI;
import java.util.UUID;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

/**
 * One LocalStack (S3) container shared by all tests of the JVM. Its endpoint and the test
 * credentials are also published as the system properties {@code s3.test.endpoint}, {@code
 * s3.test.accessKey} and {@code s3.test.secretKey}, which the engine configuration reads.
 */
final class LocalStackSupport {

    static final LocalStackContainer CONTAINER =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("s3");

    static {
        CONTAINER.start();
        System.setProperty("s3.test.endpoint", CONTAINER.getEndpoint().toString());
        System.setProperty("s3.test.accessKey", CONTAINER.getAccessKey());
        System.setProperty("s3.test.secretKey", CONTAINER.getSecretKey());
    }

    private LocalStackSupport() {}

    /** Forces the class initialisation (starts the container). */
    static void ensureStarted() {
        // the static initializer did the work
    }

    static String endpoint() {
        return CONTAINER.getEndpoint().toString();
    }

    static S3StoreConfig storeConfig(String bucket) {
        S3StoreConfig config = new S3StoreConfig();
        config.setEndpoint(endpoint());
        config.setRegion(CONTAINER.getRegion());
        config.setBucket(bucket);
        config.setAccessKey(CONTAINER.getAccessKey());
        config.setSecretKey(CONTAINER.getSecretKey());
        return config;
    }

    /** Creates a bucket with a unique name and returns the name. */
    static String newBucket(boolean versioned) {
        String bucket = "test-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3Client admin = admin()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            if (versioned) {
                admin.putBucketVersioning(b -> b.bucket(bucket)
                        .versioningConfiguration(VersioningConfiguration.builder()
                                .status(BucketVersioningStatus.ENABLED)
                                .build()));
            }
        }
        return bucket;
    }

    /** A client that bypasses the code under test, for arranging and asserting. */
    static S3Client admin() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.of(CONTAINER.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(CONTAINER.getAccessKey(), CONTAINER.getSecretKey())))
                .build();
    }
}

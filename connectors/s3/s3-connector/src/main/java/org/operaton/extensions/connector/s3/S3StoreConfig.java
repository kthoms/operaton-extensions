/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration of one named S3 store (a bucket on AWS S3 or an S3-compatible service). A plain
 * mutable bean so it binds from Spring Boot properties, XML or code; {@link S3StoreRegistry}
 * validates it at startup and never mutates it afterwards. The credentials never appear in
 * {@link #toString()}.
 *
 * <p>Credentials: when neither {@code accessKey} nor {@code profile} is set, the AWS default
 * provider chain is used (environment, system properties, shared profile, container and instance
 * credentials, web identity).
 */
public class S3StoreConfig {

    /** Default limit for {@code s3-get}: 10 MiB. */
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

    /** S3 does not allow presigned URLs that live longer than seven days. */
    public static final Duration MAX_PRESIGN_EXPIRY = Duration.ofDays(7);

    private String endpoint;
    private String publicEndpoint;
    private String region;
    private String bucket;
    private Boolean pathStyleAccess;
    private String accessKey;
    private String secretKey;
    private String sessionToken;
    private String profile;
    private long maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES;
    private Duration presignExpiry = Duration.ofMinutes(15);
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration socketTimeout = Duration.ofSeconds(60);

    /** Endpoint override for S3-compatible services (MinIO, Ceph, LocalStack); unset for AWS. */
    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = blankToNull(endpoint);
    }

    /** Endpoint used in presigned URLs when clients reach the store under another address. */
    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = blankToNull(publicEndpoint);
    }

    /** AWS region; defaults to {@code us-east-1} with an endpoint override, else the AWS chain. */
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = blankToNull(region);
    }

    /** Default bucket; a call may override it. */
    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = blankToNull(bucket);
    }

    /** Path-style addressing; defaults to {@code true} with an endpoint override, else virtual-host. */
    public Boolean getPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(Boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = blankToNull(accessKey);
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = blankToNull(secretKey);
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = blankToNull(sessionToken);
    }

    /** Named profile of the shared AWS credentials/config files. */
    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = blankToNull(profile);
    }

    /** Largest object {@code s3-get} loads, in bytes; larger objects fail the call. */
    public long getMaxDownloadBytes() {
        return maxDownloadBytes;
    }

    public void setMaxDownloadBytes(long maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
    }

    /** Default lifetime of presigned URLs. */
    public Duration getPresignExpiry() {
        return presignExpiry;
    }

    public void setPresignExpiry(Duration presignExpiry) {
        this.presignExpiry = presignExpiry;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(Duration socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    /** Checks the configuration; throws {@link S3ConnectorException} naming the store and the problem. */
    void validate(String storeName) {
        String where = "S3 store '" + storeName + "': ";
        checkUri(where, "endpoint", endpoint);
        checkUri(where, "public-endpoint", publicEndpoint);
        if ((accessKey == null) != (secretKey == null)) {
            throw new S3ConnectorException(where + "access-key and secret-key must be set together");
        }
        if (sessionToken != null && accessKey == null) {
            throw new S3ConnectorException(where + "session-token requires access-key and secret-key");
        }
        if (accessKey != null && profile != null) {
            throw new S3ConnectorException(where + "access-key and profile are mutually exclusive");
        }
        if (maxDownloadBytes <= 0) {
            throw new S3ConnectorException(where + "max-download-bytes must be positive, was " + maxDownloadBytes);
        }
        checkPositive(where, "presign-expiry", presignExpiry);
        if (presignExpiry.compareTo(MAX_PRESIGN_EXPIRY) > 0) {
            throw new S3ConnectorException(where + "presign-expiry must not exceed 7 days, was " + presignExpiry);
        }
        checkPositive(where, "connect-timeout", connectTimeout);
        checkPositive(where, "socket-timeout", socketTimeout);
    }

    private static void checkPositive(String where, String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new S3ConnectorException(where + property + " must be a positive duration, was " + value);
        }
    }

    private static void checkUri(String where, String property, String value) {
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("scheme and host are required");
            }
        } catch (IllegalArgumentException e) {
            throw new S3ConnectorException(where + property + " is not a valid URL: " + value, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        return "S3StoreConfig[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", profile="
                + profile + ", credentials=" + (accessKey != null ? "static" : "provider-chain") + "]";
    }
}

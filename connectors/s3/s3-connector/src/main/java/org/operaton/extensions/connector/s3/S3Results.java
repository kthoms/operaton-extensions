/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Result types of {@link S3Store} operations. */
public final class S3Results {

    private S3Results() {}

    /** Result of an upload. {@code versionId} is {@code null} on unversioned buckets. */
    public record PutResult(String bucket, String key, String etag, String versionId) {}

    /** A downloaded object; {@code content} is never larger than the configured limit. */
    public record GetResult(
            String bucket,
            String key,
            byte[] content,
            String contentType,
            long contentLength,
            String etag,
            String versionId,
            Instant lastModified,
            Map<String, String> metadata) {}

    /** One entry of a listing. */
    public record ObjectSummary(String key, long size, String etag, Instant lastModified, String storageClass) {}

    /** Result of a listing; {@code nextContinuationToken} is set when {@code truncated}. */
    public record ListResult(
            String bucket,
            String prefix,
            List<ObjectSummary> objects,
            List<String> commonPrefixes,
            boolean truncated,
            String nextContinuationToken) {}

    /** Result of a copy or move; {@code key} is the target key. */
    public record CopyResult(
            String sourceBucket,
            String sourceKey,
            String bucket,
            String key,
            String etag,
            String versionId,
            boolean moved) {}

    /** Result of a delete. */
    public record DeleteResult(String bucket, String key, String versionId, boolean deleteMarker) {}

    /** A presigned URL; {@code method} is {@code GET} or {@code PUT}. */
    public record PresignResult(
            String bucket,
            String key,
            String method,
            String url,
            Instant expiresAt,
            Map<String, List<String>> signedHeaders) {}
}

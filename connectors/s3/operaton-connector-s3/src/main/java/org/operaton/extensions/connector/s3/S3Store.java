/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.operaton.extensions.connector.s3.S3Results.CopyResult;
import org.operaton.extensions.connector.s3.S3Results.DeleteResult;
import org.operaton.extensions.connector.s3.S3Results.GetResult;
import org.operaton.extensions.connector.s3.S3Results.ListResult;
import org.operaton.extensions.connector.s3.S3Results.ObjectSummary;
import org.operaton.extensions.connector.s3.S3Results.PresignResult;
import org.operaton.extensions.connector.s3.S3Results.PutResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * One configured S3 store: the operations of the connector on top of the AWS SDK v2 {@link
 * S3Client} and {@link S3Presigner}. Instances are created by {@link S3StoreRegistry}.
 *
 * <p>Thread-safe: the SDK clients are thread-safe and this class holds no other mutable state.
 * Every failure is thrown as {@link S3ConnectorException} (or a subclass) naming the operation,
 * bucket and key.
 */
public final class S3Store implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(S3Store.class);
    private static final String DEFAULT_REGION = "us-east-1";

    private final String name;
    private final S3StoreConfig config;
    private final AwsCredentialsProvider credentials;
    private final S3Client client;
    private final S3Presigner presigner;

    S3Store(String name, S3StoreConfig config) {
        this.name = name;
        this.config = config;
        this.credentials = credentialsOf(config);
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle(config))
                .build();
        String regionName =
                config.getRegion() != null ? config.getRegion() : config.getEndpoint() != null ? DEFAULT_REGION : null;
        Region region = regionName != null ? Region.of(regionName) : null;
        var clientBuilder = S3Client.builder()
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(config.getConnectTimeout())
                        .socketTimeout(config.getSocketTimeout()));
        var presignerBuilder =
                S3Presigner.builder().credentialsProvider(credentials).serviceConfiguration(s3Configuration);
        if (region != null) {
            clientBuilder.region(region);
            presignerBuilder.region(region);
        }
        if (config.getEndpoint() != null) {
            clientBuilder.endpointOverride(URI.create(config.getEndpoint()));
        }
        String presignEndpoint = config.getPublicEndpoint() != null ? config.getPublicEndpoint() : config.getEndpoint();
        if (presignEndpoint != null) {
            presignerBuilder.endpointOverride(URI.create(presignEndpoint));
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    /** The name this store is configured under. */
    public String name() {
        return name;
    }

    /** The store's configuration (read only by convention; never modify it). */
    public S3StoreConfig config() {
        return config;
    }

    /** Uploads {@code content}. {@code metadata} may be {@code null}. */
    public PutResult put(String bucket, String key, byte[] content, String contentType, Map<String, String> metadata) {
        String b = bucket(bucket);
        requireKey("put", b, key);
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(b).key(key);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (metadata != null && !metadata.isEmpty()) {
            request.metadata(metadata);
        }
        try {
            PutObjectResponse response = client.putObject(request.build(), RequestBody.fromBytes(content));
            LOG.debug("Stored {} bytes at {}", content.length, uri(b, key));
            return new PutResult(b, key, unquote(response.eTag()), response.versionId());
        } catch (SdkException e) {
            throw failure("upload to", b, key, e);
        }
    }

    /**
     * Downloads an object into memory. Fails with {@link S3ObjectTooLargeException} when the object
     * is larger than {@code maxBytes} (or the store's limit when {@code maxBytes} is {@code null}).
     */
    public GetResult get(String bucket, String key, String versionId, Long maxBytes) {
        String b = bucket(bucket);
        requireKey("get", b, key);
        long limit = maxBytes != null ? maxBytes : config.getMaxDownloadBytes();
        if (limit <= 0) {
            throw new S3ConnectorException(
                    "Cannot download " + uri(b, key) + ": the size limit must be positive, was " + limit);
        }
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(b).key(key);
        if (versionId != null) {
            request.versionId(versionId);
        }
        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(request.build())) {
            GetObjectResponse response = stream.response();
            Long declared = response.contentLength();
            if (declared != null && declared > limit) {
                stream.abort();
                throw tooLarge(b, key, declared, limit);
            }
            byte[] content = stream.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
            if (content.length > limit) {
                stream.abort();
                throw tooLarge(b, key, content.length, limit);
            }
            LOG.debug("Fetched {} bytes from {}", content.length, uri(b, key));
            return new GetResult(
                    b,
                    key,
                    content,
                    response.contentType(),
                    content.length,
                    unquote(response.eTag()),
                    response.versionId(),
                    response.lastModified(),
                    response.metadata());
        } catch (NoSuchKeyException e) {
            throw new S3ObjectNotFoundException("Object " + uri(b, key) + " does not exist", e);
        } catch (S3ConnectorException e) {
            throw e;
        } catch (SdkException | IOException e) {
            throw failure("download from", b, key, e);
        }
    }

    /** Lists objects below {@code prefix}; {@code delimiter} groups keys into common prefixes. */
    public ListResult list(String bucket, String prefix, Integer maxKeys, String delimiter, String continuationToken) {
        String b = bucket(bucket);
        int max = maxKeys != null ? maxKeys : 1000;
        if (max < 1 || max > 1000) {
            throw new S3ConnectorException("Cannot list " + uri(b, prefix == null ? "" : prefix)
                    + ": max-keys must be between 1 and 1000, was " + max);
        }
        ListObjectsV2Request.Builder request =
                ListObjectsV2Request.builder().bucket(b).maxKeys(max);
        if (prefix != null) {
            request.prefix(prefix);
        }
        if (delimiter != null) {
            request.delimiter(delimiter);
        }
        if (continuationToken != null) {
            request.continuationToken(continuationToken);
        }
        try {
            ListObjectsV2Response response = client.listObjectsV2(request.build());
            List<ObjectSummary> objects = new ArrayList<>(response.contents().size());
            for (S3Object object : response.contents()) {
                objects.add(new ObjectSummary(
                        object.key(),
                        object.size() == null ? 0 : object.size(),
                        unquote(object.eTag()),
                        object.lastModified(),
                        object.storageClassAsString()));
            }
            List<String> prefixes =
                    response.commonPrefixes().stream().map(CommonPrefix::prefix).toList();
            return new ListResult(
                    b,
                    prefix,
                    List.copyOf(objects),
                    prefixes,
                    Boolean.TRUE.equals(response.isTruncated()),
                    response.nextContinuationToken());
        } catch (SdkException e) {
            throw failure("list", b, prefix == null ? "" : prefix, e);
        }
    }

    /** Deletes an object (or one version). Deleting a missing key succeeds, as in S3 itself. */
    public DeleteResult delete(String bucket, String key, String versionId) {
        String b = bucket(bucket);
        requireKey("delete", b, key);
        DeleteObjectRequest.Builder request =
                DeleteObjectRequest.builder().bucket(b).key(key);
        if (versionId != null) {
            request.versionId(versionId);
        }
        try {
            DeleteObjectResponse response = client.deleteObject(request.build());
            LOG.debug("Deleted {}", uri(b, key));
            return new DeleteResult(b, key, response.versionId(), Boolean.TRUE.equals(response.deleteMarker()));
        } catch (SdkException e) {
            throw failure("delete", b, key, e);
        }
    }

    /**
     * Copies an object server side; with {@code move} the source is deleted after the copy
     * succeeded. When {@code contentType} or {@code metadata} is given they replace the source's.
     */
    public CopyResult copy(
            String sourceBucket,
            String sourceKey,
            String targetBucket,
            String targetKey,
            String sourceVersionId,
            boolean move,
            String contentType,
            Map<String, String> metadata) {
        String sb = bucket(sourceBucket);
        String tb = targetBucket != null ? targetBucket : sb;
        requireKey("copy", sb, sourceKey);
        requireKey("copy", tb, targetKey);
        if (sb.equals(tb) && sourceKey.equals(targetKey)) {
            throw new S3ConnectorException("Cannot copy " + uri(sb, sourceKey) + " onto itself");
        }
        CopyObjectRequest.Builder request = CopyObjectRequest.builder()
                .sourceBucket(sb)
                .sourceKey(sourceKey)
                .destinationBucket(tb)
                .destinationKey(targetKey);
        if (sourceVersionId != null) {
            request.sourceVersionId(sourceVersionId);
        }
        if (contentType != null || (metadata != null && !metadata.isEmpty())) {
            request.metadataDirective(MetadataDirective.REPLACE);
            if (contentType != null) {
                request.contentType(contentType);
            }
            if (metadata != null) {
                request.metadata(metadata);
            }
        }
        try {
            CopyObjectResponse response = client.copyObject(request.build());
            if (move) {
                client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(sb)
                        .key(sourceKey)
                        .versionId(sourceVersionId)
                        .build());
            }
            LOG.debug("{} {} to {}", move ? "Moved" : "Copied", uri(sb, sourceKey), uri(tb, targetKey));
            return new CopyResult(
                    sb,
                    sourceKey,
                    tb,
                    targetKey,
                    unquote(response.copyObjectResult().eTag()),
                    response.versionId(),
                    move);
        } catch (NoSuchKeyException e) {
            throw new S3ObjectNotFoundException("Object " + uri(sb, sourceKey) + " does not exist", e);
        } catch (SdkException e) {
            throw failure(
                    (move ? "move " + uri(sb, sourceKey) + " to" : "copy " + uri(sb, sourceKey) + " to"),
                    tb,
                    targetKey,
                    e);
        }
    }

    /**
     * Creates a presigned URL for {@code method} ({@code GET} or {@code PUT}). Nothing is sent to
     * the store, so the object need not exist. For {@code GET}, {@code fileName} makes browsers
     * save the download under that name; for {@code PUT}, {@code contentType} is signed and the
     * uploader must send the same {@code Content-Type}.
     */
    public PresignResult presign(
            String bucket, String key, String method, Duration expiry, String contentType, String fileName) {
        String b = bucket(bucket);
        requireKey("presign", b, key);
        Duration lifetime = expiry != null ? expiry : config.getPresignExpiry();
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(S3StoreConfig.MAX_PRESIGN_EXPIRY) > 0) {
            throw new S3ConnectorException("Cannot presign " + uri(b, key)
                    + ": the expiry must be between 1 second and 7 days, was " + lifetime);
        }
        String verb = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        try {
            switch (verb) {
                case "GET": {
                    GetObjectRequest.Builder object =
                            GetObjectRequest.builder().bucket(b).key(key);
                    if (fileName != null) {
                        object.responseContentDisposition(
                                "attachment; filename=\"" + fileName.replace("\"", "") + "\"");
                    }
                    PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(lifetime)
                            .getObjectRequest(object.build())
                            .build());
                    return new PresignResult(
                            b,
                            key,
                            verb,
                            presigned.url().toString(),
                            presigned.expiration(),
                            presigned.signedHeaders());
                }
                case "PUT": {
                    PutObjectRequest.Builder object =
                            PutObjectRequest.builder().bucket(b).key(key);
                    if (contentType != null) {
                        object.contentType(contentType);
                    }
                    PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(lifetime)
                            .putObjectRequest(object.build())
                            .build());
                    return new PresignResult(
                            b,
                            key,
                            verb,
                            presigned.url().toString(),
                            presigned.expiration(),
                            presigned.signedHeaders());
                }
                default:
                    throw new S3ConnectorException(
                            "Cannot presign " + uri(b, key) + ": the method must be GET or PUT, was '" + method + "'");
            }
        } catch (SdkException e) {
            throw failure("presign " + verb + " for", b, key, e);
        }
    }

    @Override
    public void close() {
        client.close();
        presigner.close();
        if (credentials instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("Closing the credentials provider of S3 store '{}' failed", name, e);
            }
        }
    }

    private String bucket(String bucket) {
        String resolved = bucket != null ? bucket : config.getBucket();
        if (resolved == null) {
            throw new S3ConnectorException(
                    "No bucket: set 'bucket' on the call or 'bucket' on S3 store '" + name + "'");
        }
        return resolved;
    }

    private static void requireKey(String operation, String bucket, String key) {
        if (key == null || key.isEmpty()) {
            throw new S3ConnectorException("Cannot " + operation + " in bucket '" + bucket + "': 'key' is required");
        }
    }

    private static S3ObjectTooLargeException tooLarge(String bucket, String key, long size, long limit) {
        return new S3ObjectTooLargeException(
                "Object " + uri(bucket, key) + " is "
                        + (size > limit && size < Integer.MAX_VALUE ? size + " bytes" : "larger than the limit")
                        + ", above the download limit of " + limit
                        + " bytes; raise 'max-download-bytes' (or 'maxSize' on the call) or read the object through a presigned URL");
    }

    private static S3ConnectorException failure(String operation, String bucket, String key, Exception cause) {
        String detail = cause instanceof S3Exception s3 && s3.awsErrorDetails() != null
                ? s3.awsErrorDetails().errorCode() + " (HTTP " + s3.statusCode() + ")"
                : cause.getClass().getSimpleName();
        return new S3ConnectorException(
                "Cannot " + operation + " " + uri(bucket, key) + ": " + detail + ": " + cause.getMessage(), cause);
    }

    private static String uri(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    private static String unquote(String etag) {
        if (etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            return etag.substring(1, etag.length() - 1);
        }
        return etag;
    }

    private static boolean pathStyle(S3StoreConfig config) {
        return config.getPathStyleAccess() != null ? config.getPathStyleAccess() : config.getEndpoint() != null;
    }

    private static AwsCredentialsProvider credentialsOf(S3StoreConfig config) {
        if (config.getAccessKey() != null) {
            return StaticCredentialsProvider.create(
                    config.getSessionToken() != null
                            ? AwsSessionCredentials.create(
                                    config.getAccessKey(), config.getSecretKey(), config.getSessionToken())
                            : AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey()));
        }
        if (config.getProfile() != null) {
            return ProfileCredentialsProvider.create(config.getProfile());
        }
        return DefaultCredentialsProvider.builder().build();
    }
}

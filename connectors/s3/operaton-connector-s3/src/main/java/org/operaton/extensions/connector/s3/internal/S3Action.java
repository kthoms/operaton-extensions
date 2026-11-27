/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.operaton.extensions.connector.s3.S3Download;
import org.operaton.extensions.connector.s3.S3Results.CopyResult;
import org.operaton.extensions.connector.s3.S3Results.DeleteResult;
import org.operaton.extensions.connector.s3.S3Results.GetResult;
import org.operaton.extensions.connector.s3.S3Results.ListResult;
import org.operaton.extensions.connector.s3.S3Results.ObjectSummary;
import org.operaton.extensions.connector.s3.S3Results.PresignResult;
import org.operaton.extensions.connector.s3.S3Results.PutResult;
import org.operaton.extensions.connector.s3.S3Store;
import org.operaton.extensions.connector.s3.S3StoreRegistry;

/**
 * The six operations, shared by the connectors and the delegates: request parameters in, response
 * parameters out. Not API; the parameters and results are documented in the README.
 */
public enum S3Action {
    PUT("s3-put") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            S3Content content = S3Content.of(id, p.raw("content"));
            String contentType = p.string("contentType") != null ? p.string("contentType") : content.contentType();
            PutResult r = store.put(
                    p.string("bucket"), p.requiredString("key"), content.bytes(), contentType, p.stringMap("metadata"));
            Map<String, Object> out = new HashMap<>();
            out.put("bucket", r.bucket());
            out.put("key", r.key());
            out.put("etag", r.etag());
            out.put("versionId", r.versionId());
            return out;
        }
    },
    GET("s3-get") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            GetResult r = store.get(
                    p.string("bucket"), p.requiredString("key"), p.string("versionId"), p.longValue("maxSize"));
            Map<String, Object> out = new HashMap<>();
            out.put("download", new S3Download(r.content(), lastSegment(r.key()), r.contentType()));
            out.put("bucket", r.bucket());
            out.put("key", r.key());
            out.put("contentType", r.contentType());
            out.put("contentLength", r.contentLength());
            out.put("etag", r.etag());
            out.put("versionId", r.versionId());
            out.put("lastModified", iso(r.lastModified()));
            out.put("metadata", r.metadata());
            out.put("metadataJson", Json.object(r.metadata()));
            return out;
        }
    },
    LIST("s3-list") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            ListResult r = store.list(
                    p.string("bucket"),
                    p.string("prefix"),
                    p.intValue("maxKeys"),
                    p.string("delimiter"),
                    p.string("continuationToken"));
            List<String> keys = r.objects().stream().map(ObjectSummary::key).toList();
            List<Map<String, Object>> objects = new ArrayList<>();
            for (ObjectSummary o : r.objects()) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("key", o.key());
                entry.put("size", o.size());
                entry.put("etag", o.etag());
                entry.put("lastModified", iso(o.lastModified()));
                entry.put("storageClass", o.storageClass());
                objects.add(entry);
            }
            Map<String, Object> out = new HashMap<>();
            out.put("bucket", r.bucket());
            out.put("prefix", r.prefix());
            out.put("keys", keys);
            out.put("keysJson", Json.array(keys));
            out.put("objects", objects);
            out.put("objectsJson", Json.arrayOfObjects(objects));
            out.put("commonPrefixes", r.commonPrefixes());
            out.put("commonPrefixesJson", Json.array(r.commonPrefixes()));
            out.put("count", keys.size());
            out.put("truncated", r.truncated());
            out.put("nextContinuationToken", r.nextContinuationToken());
            return out;
        }
    },
    DELETE("s3-delete") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            DeleteResult r = store.delete(p.string("bucket"), p.requiredString("key"), p.string("versionId"));
            Map<String, Object> out = new HashMap<>();
            out.put("bucket", r.bucket());
            out.put("key", r.key());
            out.put("versionId", r.versionId());
            out.put("deleteMarker", r.deleteMarker());
            return out;
        }
    },
    COPY("s3-copy") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            CopyResult r = store.copy(
                    p.string("sourceBucket") != null ? p.string("sourceBucket") : p.string("bucket"),
                    p.requiredString("sourceKey"),
                    p.string("targetBucket"),
                    p.requiredString("targetKey"),
                    p.string("sourceVersionId"),
                    p.bool("move", false),
                    p.string("contentType"),
                    p.stringMap("metadata"));
            Map<String, Object> out = new HashMap<>();
            out.put("sourceBucket", r.sourceBucket());
            out.put("sourceKey", r.sourceKey());
            out.put("bucket", r.bucket());
            out.put("key", r.key());
            out.put("etag", r.etag());
            out.put("versionId", r.versionId());
            out.put("moved", r.moved());
            return out;
        }
    },
    PRESIGN("s3-presign") {
        @Override
        Map<String, Object> run(S3Store store, S3Params p) {
            PresignResult r = store.presign(
                    p.string("bucket"),
                    p.requiredString("key"),
                    p.string("method"),
                    p.duration("expiresIn"),
                    p.string("contentType"),
                    p.string("fileName"));
            Map<String, Object> out = new HashMap<>();
            out.put("bucket", r.bucket());
            out.put("key", r.key());
            out.put("method", r.method());
            out.put("url", r.url());
            out.put("expiresAt", iso(r.expiresAt()));
            out.put("signedHeaders", r.signedHeaders());
            return out;
        }
    };

    final String id;

    S3Action(String id) {
        this.id = id;
    }

    /** The connector id, for example {@code s3-put}. */
    public String id() {
        return id;
    }

    /** The action behind a connector id. */
    public static S3Action byId(String id) {
        for (S3Action action : values()) {
            if (action.id.equals(id)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown S3 connector id '" + id + "'");
    }

    /** Runs the action against the registry of the current engine. */
    public Map<String, Object> execute(Map<String, Object> parameters) {
        return execute(S3StoreRegistry.current(), parameters);
    }

    /** Runs the action against {@code registry}; the store comes from the {@code store} parameter. */
    public Map<String, Object> execute(S3StoreRegistry registry, Map<String, Object> parameters) {
        S3Params params = new S3Params(id, parameters);
        return run(registry.store(params.string("store")), params);
    }

    abstract Map<String, Object> run(S3Store store, S3Params params);

    private static String lastSegment(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    @Override
    public String toString() {
        return id.toLowerCase(Locale.ROOT);
    }
}

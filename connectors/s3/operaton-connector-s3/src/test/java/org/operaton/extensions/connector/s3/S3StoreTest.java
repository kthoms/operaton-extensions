/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.s3.S3Results.CopyResult;
import org.operaton.extensions.connector.s3.S3Results.GetResult;
import org.operaton.extensions.connector.s3.S3Results.ListResult;
import org.operaton.extensions.connector.s3.S3Results.PresignResult;
import org.operaton.extensions.connector.s3.S3Results.PutResult;

/** The store operations against a real S3 implementation (LocalStack). */
class S3StoreTest {

    private static String bucket;
    private static String versionedBucket;
    private static S3StoreRegistry registry;
    private static S3Store store;

    @BeforeAll
    static void setUp() {
        LocalStackSupport.ensureStarted();
        bucket = LocalStackSupport.newBucket(false);
        versionedBucket = LocalStackSupport.newBucket(true);
        S3StoreConfig config = LocalStackSupport.storeConfig(bucket);
        config.setMaxDownloadBytes(1024);
        registry = new S3StoreRegistry(Map.of("main", config), "main");
        store = registry.store(null);
    }

    @AfterAll
    static void tearDown() {
        registry.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putThenGetRoundTripsContentTypeAndMetadata() {
        PutResult put = store.put(null, "docs/a.txt", bytes("hello"), "text/plain", Map.of("owner", "alice"));

        assertThat(put.bucket()).isEqualTo(bucket);
        assertThat(put.key()).isEqualTo("docs/a.txt");
        assertThat(put.etag()).isNotBlank().doesNotContain("\"");
        assertThat(put.versionId()).isNull();

        GetResult get = store.get(null, "docs/a.txt", null, null);
        assertThat(get.content()).isEqualTo(bytes("hello"));
        assertThat(get.contentType()).isEqualTo("text/plain");
        assertThat(get.contentLength()).isEqualTo(5);
        assertThat(get.etag()).isEqualTo(put.etag());
        assertThat(get.metadata()).containsEntry("owner", "alice");
        assertThat(get.lastModified())
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void getFailsLoudlyAboveTheSizeLimit() {
        store.put(null, "big/object.bin", new byte[2048], "application/octet-stream", null);

        assertThatThrownBy(() -> store.get(null, "big/object.bin", null, null))
                .isInstanceOf(S3ObjectTooLargeException.class)
                .hasMessageContaining("s3://" + bucket + "/big/object.bin")
                .hasMessageContaining("2048")
                .hasMessageContaining("1024");
        assertThat(store.get(null, "big/object.bin", null, 4096L).content()).hasSize(2048);
        assertThatThrownBy(() -> store.get(null, "big/object.bin", null, 100L))
                .isInstanceOf(S3ObjectTooLargeException.class);
    }

    @Test
    void getOfAMissingKeyIsNotFound() {
        assertThatThrownBy(() -> store.get(null, "nothing/here", null, null))
                .isInstanceOf(S3ObjectNotFoundException.class)
                .hasMessageContaining("s3://" + bucket + "/nothing/here");
    }

    @Test
    void operationsOnAMissingBucketFailWithContext() {
        assertThatThrownBy(() -> store.put("no-such-bucket", "k", bytes("x"), null, null))
                .isInstanceOf(S3ConnectorException.class)
                .hasMessageContaining("s3://no-such-bucket/k")
                .hasMessageContaining("NoSuchBucket");
    }

    @Test
    void listsByPrefixWithPagingAndDelimiter() {
        for (int i = 0; i < 5; i++) {
            store.put(null, "list/file-" + i + ".txt", bytes("x" + i), "text/plain", null);
        }
        store.put(null, "list/sub/inner.txt", bytes("inner"), "text/plain", null);
        store.put(null, "other/skipped.txt", bytes("no"), "text/plain", null);

        ListResult all = store.list(null, "list/", null, null, null);
        assertThat(all.objects())
                .extracting(S3Results.ObjectSummary::key)
                .hasSize(6)
                .allMatch(k -> k.startsWith("list/"));
        assertThat(all.truncated()).isFalse();
        assertThat(all.objects().get(0).size()).isPositive();

        ListResult first = store.list(null, "list/", 4, null, null);
        assertThat(first.objects()).hasSize(4);
        assertThat(first.truncated()).isTrue();
        ListResult second = store.list(null, "list/", 4, null, first.nextContinuationToken());
        assertThat(second.objects()).hasSize(2);
        assertThat(second.truncated()).isFalse();

        ListResult folders = store.list(null, "list/", null, "/", null);
        assertThat(folders.commonPrefixes()).containsExactly("list/sub/");
        assertThat(folders.objects()).hasSize(5);
    }

    @Test
    void listRejectsAnOutOfRangeMaxKeys() {
        assertThatThrownBy(() -> store.list(null, "x", 0, null, null)).hasMessageContaining("max-keys");
        assertThatThrownBy(() -> store.list(null, "x", 5000, null, null)).hasMessageContaining("max-keys");
    }

    @Test
    void copyKeepsTheSourceAndMoveRemovesIt() {
        store.put(null, "in/a.txt", bytes("payload"), "text/plain", Map.of("k", "v"));

        CopyResult copy = store.copy(null, "in/a.txt", null, "copy/a.txt", null, false, null, null);
        assertThat(copy.moved()).isFalse();
        assertThat(store.get(null, "in/a.txt", null, null).content()).isEqualTo(bytes("payload"));
        GetResult copied = store.get(null, "copy/a.txt", null, null);
        assertThat(copied.content()).isEqualTo(bytes("payload"));
        assertThat(copied.metadata()).containsEntry("k", "v");

        CopyResult move = store.copy(
                null, "in/a.txt", null, "archive/a.txt", null, true, "text/markdown", Map.of("state", "archived"));
        assertThat(move.moved()).isTrue();
        assertThatThrownBy(() -> store.get(null, "in/a.txt", null, null)).isInstanceOf(S3ObjectNotFoundException.class);
        GetResult archived = store.get(null, "archive/a.txt", null, null);
        assertThat(archived.contentType()).isEqualTo("text/markdown");
        assertThat(archived.metadata()).containsEntry("state", "archived").doesNotContainKey("k");
    }

    @Test
    void copyOfAMissingSourceIsNotFoundAndOntoItselfIsRefused() {
        assertThatThrownBy(() -> store.copy(null, "missing/src", null, "dst", null, true, null, null))
                .isInstanceOf(S3ObjectNotFoundException.class);
        store.put(null, "same.txt", bytes("x"), null, null);
        assertThatThrownBy(() -> store.copy(null, "same.txt", null, "same.txt", null, true, null, null))
                .hasMessageContaining("onto itself");
        assertThat(store.get(null, "same.txt", null, null).content()).isEqualTo(bytes("x"));
    }

    @Test
    void deleteIsIdempotent() {
        store.put(null, "gone.txt", bytes("x"), null, null);
        assertThat(store.delete(null, "gone.txt", null).key()).isEqualTo("gone.txt");
        assertThatThrownBy(() -> store.get(null, "gone.txt", null, null)).isInstanceOf(S3ObjectNotFoundException.class);
        assertThat(store.delete(null, "gone.txt", null).bucket()).isEqualTo(bucket);
    }

    @Test
    void versionedBucketsReturnAndHonourVersionIds() {
        PutResult v1 = store.put(versionedBucket, "v.txt", bytes("one"), "text/plain", null);
        PutResult v2 = store.put(versionedBucket, "v.txt", bytes("two"), "text/plain", null);

        assertThat(v1.versionId()).isNotBlank().isNotEqualTo(v2.versionId());
        assertThat(store.get(versionedBucket, "v.txt", null, null).content()).isEqualTo(bytes("two"));
        assertThat(store.get(versionedBucket, "v.txt", v1.versionId(), null).content())
                .isEqualTo(bytes("one"));
    }

    @Test
    void presignedGetUrlDownloadsAndExpiryIsReported() throws Exception {
        store.put(null, "share/report.pdf", bytes("pdf-bytes"), "application/pdf", null);

        PresignResult presigned =
                store.presign(null, "share/report.pdf", "GET", Duration.ofMinutes(5), null, "report.pdf");

        assertThat(presigned.method()).isEqualTo("GET");
        assertThat(presigned.url()).startsWith(LocalStackSupport.endpoint()).contains("share/report.pdf");
        assertThat(presigned.expiresAt())
                .isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(360));
        HttpResponse<byte[]> response =
                send(HttpRequest.newBuilder(URI.create(presigned.url())).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes("pdf-bytes"));
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(v -> assertThat(v).contains("report.pdf"));
    }

    @Test
    void presignedPutUrlUploads() throws Exception {
        PresignResult presigned = store.presign(null, "upload/new.txt", "PUT", null, "text/plain", null);

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(presigned.url()))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("uploaded"))
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(store.get(null, "upload/new.txt", null, null).content()).isEqualTo(bytes("uploaded"));
    }

    @Test
    void presignValidatesMethodAndExpiry() {
        assertThatThrownBy(() -> store.presign(null, "k", "POST", null, null, null))
                .hasMessageContaining("GET or PUT");
        assertThatThrownBy(() -> store.presign(null, "k", "GET", Duration.ofDays(8), null, null))
                .hasMessageContaining("7 days");
        assertThatThrownBy(() -> store.presign(null, "k", "GET", Duration.ZERO, null, null))
                .hasMessageContaining("expiry");
    }

    @Test
    void publicEndpointIsUsedForPresignedUrlsOnly() {
        S3StoreConfig config = LocalStackSupport.storeConfig(bucket);
        config.setPublicEndpoint("https://files.example.org");
        try (S3StoreRegistry other = new S3StoreRegistry(Map.of("pub", config), null)) {
            assertThat(other.store("pub")
                            .presign(null, "k", "GET", null, null, null)
                            .url())
                    .startsWith("https://files.example.org/");
            other.store("pub").put(null, "pub.txt", bytes("ok"), null, null);
        }
    }

    @Test
    void missingBucketOrKeyIsRefusedWithoutCallingS3() {
        S3StoreConfig config = new S3StoreConfig();
        config.setEndpoint(LocalStackSupport.endpoint());
        config.setAccessKey("a");
        config.setSecretKey("b");
        try (S3StoreRegistry other = new S3StoreRegistry(Map.of("nobucket", config), null)) {
            assertThatThrownBy(() -> other.store(null).put(null, "k", bytes("x"), null, null))
                    .hasMessageContaining("No bucket");
        }
        assertThatThrownBy(() -> store.put(null, "", bytes("x"), null, null)).hasMessageContaining("'key' is required");
    }

    @Test
    void unreachableEndpointFailsLoudly() {
        S3StoreConfig config = LocalStackSupport.storeConfig(bucket);
        config.setEndpoint("http://127.0.0.1:1");
        config.setConnectTimeout(Duration.ofSeconds(2));
        try (S3StoreRegistry other = new S3StoreRegistry(Map.of("dead", config), null)) {
            assertThatThrownBy(() -> other.store(null).get(null, "k", null, null))
                    .isInstanceOf(S3ConnectorException.class)
                    .hasMessageContaining("s3://" + bucket + "/k");
        }
    }

    private static HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}

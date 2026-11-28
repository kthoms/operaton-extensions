/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.FileValue;

/** The connectors run from BPMN in an embedded engine against LocalStack. */
class S3ConnectorEngineTest extends AbstractS3EngineTest {

    @BeforeEach
    void deployProcesses() {
        deploy(
                "s3-roundtrip",
                "s3-get-missing",
                "s3-get-limited",
                "s3-other-store",
                "s3-unknown-store",
                "s3-put-without-key");
    }

    @Test
    void putPresignGetListMoveAndDeleteFromAFileVariable() throws Exception {
        String text = "Quarterly report";
        FileValue file = Variables.fileValue("report.txt")
                .file(text.getBytes(StandardCharsets.UTF_8))
                .mimeType("text/plain")
                .create();

        ProcessInstance instance = start(
                "s3-roundtrip",
                Map.of(
                        "document", file,
                        "documentName", "report.txt",
                        "objectKey", "reports/2026/report.txt",
                        "contentType", "text/plain",
                        "owner", "alice",
                        "prefix", "reports/"));

        assertThat(historic(instance, "putKey")).isEqualTo("reports/2026/report.txt");
        assertThat((String) historic(instance, "putEtag")).isNotBlank();
        assertThat(historic(instance, "putVersionId")).isNull();
        assertThat(historic(instance, "text")).isEqualTo(text);
        assertThat(historic(instance, "getContentType")).isEqualTo("text/plain");
        assertThat(historic(instance, "getLength")).isEqualTo((long) text.length());
        assertThat((String) historic(instance, "metadataJson")).isEqualTo("{\"owner\":\"alice\"}");

        FileValue downloaded = (FileValue) engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("downloaded")
                .singleResult()
                .getTypedValue();
        assertThat(downloaded.getFilename()).isEqualTo("report.txt");
        assertThat(downloaded.getMimeType()).isEqualTo("text/plain");
        try (InputStream content = downloaded.getValue()) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(text);
        }

        assertThat((String) historic(instance, "keysJson")).contains("\"reports/2026/report.txt\"");
        assertThat(historic(instance, "count")).isEqualTo(1);
        assertThat(historic(instance, "truncated")).isEqualTo(false);
        assertThat((String) historic(instance, "url")).contains("reports/2026/report.txt");
        assertThat(Instant.parse((String) historic(instance, "expiresAt"))).isAfter(Instant.now());

        // the process moved the object to archive/ and deleted it again
        assertThat(historic(instance, "moved")).isEqualTo(true);
        assertThat(historic(instance, "archivedKey")).isEqualTo("archive/report.txt");
        assertThat(historic(instance, "deletedKey")).isEqualTo("archive/report.txt");
        assertThatThrownBy(() -> getObject(BUCKET, "reports/2026/report.txt")).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> getObject(BUCKET, "archive/report.txt")).hasMessageContaining("does not exist");
    }

    @Test
    void presignedUrlOfTheProcessDownloadsTheObjectWhileItExists() throws Exception {
        ProcessInstance instance = start(
                "s3-roundtrip",
                Map.of(
                        "document",
                        new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)),
                        "documentName",
                        "streamed.txt",
                        "objectKey",
                        "link/streamed.txt",
                        "contentType",
                        "text/plain",
                        "owner",
                        "bob",
                        "prefix",
                        "link/"));
        // the object is gone at the end (moved and deleted); put it back and use the URL of the run
        putObject(BUCKET, "link/streamed.txt", "streamed".getBytes(StandardCharsets.UTF_8));

        assertThat(download((String) historic(instance, "url"))).isEqualTo("streamed".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCallCanRaiseTheSizeLimit() {
        putObject(BUCKET, "limits/big.bin", "a".repeat(3000).getBytes(StandardCharsets.UTF_8));

        ProcessInstance instance = start("s3-get-limited", Map.of("objectKey", "limits/big.bin", "maxSize", 5000L));

        assertThat((String) historic(instance, "text")).hasSize(3000);
    }

    @Test
    void getAboveTheDefaultLimitIsRefused() {
        putObject(BUCKET, "limits/over.bin", new byte[2048]);

        assertThatThrownBy(() -> start("s3-get-limited", Map.of("objectKey", "limits/over.bin", "maxSize", 1024)))
                .hasStackTraceContaining(S3ObjectTooLargeException.class.getName())
                .hasStackTraceContaining("above the download limit of 1024 bytes");
    }

    @Test
    void getOfAMissingObjectFailsTheProcessWithTheNotFoundException() {
        assertThatThrownBy(() -> start("s3-get-missing", Map.of()))
                .isInstanceOf(S3ObjectNotFoundException.class)
                .hasStackTraceContaining(S3ObjectNotFoundException.class.getName())
                .hasStackTraceContaining("missing/object.txt");
    }

    @Test
    void aCallCanSelectAnotherStore() {
        ProcessInstance instance =
                start("s3-other-store", Map.of("document", "to the other bucket", "objectKey", "other/doc.txt"));

        assertThat(historic(instance, "bucket")).isEqualTo(OTHER_BUCKET);
        assertThat(getObject(OTHER_BUCKET, "other/doc.txt"))
                .isEqualTo("to the other bucket".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownStoreAndAMissingKeyFailLoudly() {
        assertThatThrownBy(() -> start("s3-unknown-store", Map.of()))
                .hasStackTraceContaining("Unknown S3 store 'nope'")
                .hasStackTraceContaining("docs");
        assertThatThrownBy(() -> start("s3-put-without-key", Map.of()))
                .hasStackTraceContaining("s3-put: parameter 'key' is required");
    }
}

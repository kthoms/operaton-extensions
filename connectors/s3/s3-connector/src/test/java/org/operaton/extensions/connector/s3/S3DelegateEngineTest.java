/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.FileValue;

/** The JavaDelegate alternatives, instantiated by class name as in a plain engine. */
class S3DelegateEngineTest extends AbstractS3EngineTest {

    @BeforeEach
    void deployProcesses() {
        deploy("s3-delegates");
    }

    @Test
    void delegatesCoverTheSameOperationsAndKeepTheFileVariableMetadata() throws Exception {
        FileValue file = Variables.fileValue("contract.pdf")
                .file("%PDF-contract".getBytes(StandardCharsets.UTF_8))
                .mimeType("application/pdf")
                .create();

        ProcessInstance instance = start(
                "s3-delegates",
                Map.of(
                        "document", file,
                        "documentName", "contract.pdf",
                        "objectKey", "delegates/contract.pdf",
                        "contentType", "application/pdf",
                        "owner", "carol",
                        "prefix", "delegates/"));

        assertThat((String) historic(instance, "s3Etag")).isNotBlank();
        assertThat(historic(instance, "s3Key")).isEqualTo("delegates/contract.pdf");
        assertThat((String) historic(instance, "linkUrl")).contains("delegates/contract.pdf");
        assertThat(historic(instance, "s3Count")).isEqualTo(1);
        assertThat((String) historic(instance, "s3KeysJson")).isEqualTo("[\"delegates/contract.pdf\"]");
        assertThat(historic(instance, "s3ContentType")).isEqualTo("application/pdf");
        assertThat(historic(instance, "copyMoved")).isEqualTo(true);
        assertThat(historic(instance, "copyKey")).isEqualTo("archive/contract.pdf");
        assertThat(historic(instance, "delKey")).isEqualTo("archive/contract.pdf");

        FileValue downloaded = (FileValue) engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("downloaded")
                .singleResult()
                .getTypedValue();
        assertThat(downloaded.getFilename()).isEqualTo("contract.pdf");
        assertThat(downloaded.getMimeType()).isEqualTo("application/pdf");
        try (InputStream content = downloaded.getValue()) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("%PDF-contract");
        }
        assertThatThrownBy(() -> getObject(BUCKET, "archive/contract.pdf")).hasMessageContaining("does not exist");
    }

    @Test
    void aMissingContentVariableFailsLoudly() {
        assertThatThrownBy(() -> start(
                        "s3-delegates",
                        Map.of(
                                "documentName", "x",
                                "objectKey", "delegates/none",
                                "contentType", "text/plain",
                                "owner", "x",
                                "prefix", "delegates/")))
                .hasStackTraceContaining("the content variable 'document' does not exist");
    }
}

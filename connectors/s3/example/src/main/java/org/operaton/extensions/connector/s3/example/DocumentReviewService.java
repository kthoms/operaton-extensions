/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.example;

import java.util.Map;
import java.util.UUID;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.springframework.stereotype.Service;

/** Entry point of the scenario: submits a document for review. */
@Service
public class DocumentReviewService {

    public static final String PROCESS_KEY = "document-review";

    private final RuntimeService runtimeService;

    public DocumentReviewService(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * Starts a review of the document. The process stores it in S3 under {@code incoming/<id>/<name>}.
     *
     * @return the process instance id
     */
    public String submit(String name, String contentType, byte[] content, String submitter) {
        FileValue document =
                Variables.fileValue(name).file(content).mimeType(contentType).create();
        return runtimeService
                .startProcessInstanceByKey(
                        PROCESS_KEY,
                        Map.of(
                                "documentId", UUID.randomUUID().toString(),
                                "documentName", name,
                                "documentContentType", contentType,
                                "submitter", submitter,
                                "document", document))
                .getId();
    }
}

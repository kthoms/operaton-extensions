/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.extensions.connector.s3.S3ConnectorException;

/** The bytes to upload, with the content type and file name that the source value implies. Not API. */
public record S3Content(byte[] bytes, String contentType, String fileName) {

    private static final String BINARY = "application/octet-stream";

    /**
     * Converts a process or request value: {@code byte[]}, {@code String} (UTF-8), {@link
     * InputStream} (read fully and closed, as process file variables arrive), {@link FileValue},
     * {@link Path} or {@link File}.
     */
    public static S3Content of(String operation, Object value) {
        try {
            if (value == null) {
                throw new S3ConnectorException(operation + ": 'content' is required but was null");
            }
            if (value instanceof byte[] bytes) {
                return new S3Content(bytes, BINARY, null);
            }
            if (value instanceof String text) {
                return new S3Content(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", null);
            }
            if (value instanceof FileValue file) {
                try (InputStream stream = file.getValue()) {
                    if (stream == null) {
                        throw new S3ConnectorException(operation + ": the file variable has no content");
                    }
                    String type = file.getMimeType() != null ? file.getMimeType() : BINARY;
                    return new S3Content(stream.readAllBytes(), type, file.getFilename());
                }
            }
            if (value instanceof InputStream stream) {
                try (stream) {
                    return new S3Content(stream.readAllBytes(), BINARY, null);
                }
            }
            if (value instanceof Path path) {
                return new S3Content(
                        Files.readAllBytes(path), BINARY, path.getFileName().toString());
            }
            if (value instanceof File file) {
                return new S3Content(Files.readAllBytes(file.toPath()), BINARY, file.getName());
            }
        } catch (IOException e) {
            throw new S3ConnectorException(operation + ": cannot read the content to upload: " + e.getMessage(), e);
        }
        throw new S3ConnectorException(operation + ": 'content' must be a byte[], String, InputStream, file variable, "
                + "Path or File, was " + value.getClass().getName());
    }
}

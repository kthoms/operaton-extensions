/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.nio.charset.StandardCharsets;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.FileValue;

/**
 * The content of a downloaded object, returned by {@code s3-get} as response parameter {@code
 * download}. A connector output mapping cannot store a typed file value directly, so the BPMN picks
 * the representation it needs:
 *
 * <pre>{@code
 * <operaton:outputParameter name="document">${download.fileValue}</operaton:outputParameter>
 * <operaton:outputParameter name="body">${download.text}</operaton:outputParameter>
 * }</pre>
 *
 * Immutable; the byte array is shared, do not modify it.
 */
public final class S3Download {

    private final byte[] bytes;
    private final String fileName;
    private final String contentType;

    public S3Download(byte[] bytes, String fileName, String contentType) {
        this.bytes = bytes;
        this.fileName = fileName;
        this.contentType = contentType;
    }

    /** The raw bytes (stored as a byte-array variable). */
    public byte[] getBytes() {
        return bytes;
    }

    /** The content decoded as UTF-8. */
    public String getText() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public long getSize() {
        return bytes.length;
    }

    /** The last segment of the object key. */
    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    /** A file variable value carrying the content, the file name and the content type. */
    public FileValue getFileValue() {
        var builder = Variables.fileValue(fileName).file(bytes);
        if (contentType != null) {
            builder.mimeType(contentType);
        }
        return builder.create();
    }
}

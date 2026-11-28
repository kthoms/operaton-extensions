/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-mail (Apache-2.0)
 */
package org.operaton.extensions.connector.mail.dto;

import java.io.Serializable;
import java.nio.file.Path;

/**
 * An attachment of a received mail. {@link #getPath()} is set when the attachment was downloaded; {@link
 * #getContent()} only when the mail was read with content kept in memory (not serialized).
 */
public class Attachment implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String fileName;
    private final String contentType;
    private final long size;
    private String path;
    private final transient byte[] content;

    public Attachment(String fileName, String contentType, long size, byte[] content) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    /** Absolute path of the downloaded file, or {@code null} when the attachment was not downloaded. */
    public String getPath() {
        return path;
    }

    /** The bytes, or {@code null} unless the mail was read with content kept in memory. */
    public byte[] getContent() {
        return content;
    }

    void downloaded(Path file) {
        this.path = file.toAbsolutePath().toString();
    }

    @Override
    public String toString() {
        return "Attachment [fileName=" + fileName + ", contentType=" + contentType + ", size=" + size + ", path=" + path
                + "]";
    }
}

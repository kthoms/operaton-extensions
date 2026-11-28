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
package org.operaton.extensions.connector.mail.poll;

import java.util.Date;
import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailParameters;

/**
 * Request of the {@code mail-poll} connector. Input parameters, all optional:
 *
 * <ul>
 *   <li>{@code folder}: the folder (default: {@code mail.poll.folder}, {@code INBOX})
 *   <li>{@code unreadOnly}: only messages not yet marked as read (default {@code false})
 *   <li>{@code from}, {@code subject}: only messages whose sender / subject contains the text
 *   <li>{@code receivedSince}: only messages received on or after the day of this {@code java.util.Date} (IMAP compares days, not times)
 *   <li>{@code limit}: at most this many messages, oldest first
 *   <li>{@code markAsRead}: mark the returned messages as read (default {@code false})
 *   <li>{@code download-attachments} (or {@code downloadAttachments}): write attachments to {@code
 *       mail.attachment.path} (default {@code mail.attachment.download})
 * </ul>
 *
 * Deleted messages are never returned.
 */
public class PollMailRequest extends AbstractConnectorRequest<PollMailResponse> {

    protected static final String PARAM_FOLDER = "folder";
    protected static final String PARAM_UNREAD_ONLY = "unreadOnly";
    protected static final String PARAM_FROM = "from";
    protected static final String PARAM_SUBJECT = "subject";
    protected static final String PARAM_RECEIVED_SINCE = "receivedSince";
    protected static final String PARAM_LIMIT = "limit";
    protected static final String PARAM_MARK_AS_READ = "markAsRead";
    protected static final String PARAM_DOWNLOAD_ATTACHMENTS = "download-attachments";
    protected static final String PARAM_DOWNLOAD_ATTACHMENTS_CAMEL = "downloadAttachments";

    private final AbstractMailConnector<?, ?> mailConnector;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public PollMailRequest(AbstractMailConnector<?, ?> connector) {
        super((Connector) connector);
        this.mailConnector = connector;
    }

    public String getFolder() {
        String folder = MailParameters.string(PARAM_FOLDER, getRequestParameter(PARAM_FOLDER));
        return folder != null ? folder : mailConnector.mailService().settings().pollFolder();
    }

    public PollMailRequest folder(String folder) {
        setRequestParameter(PARAM_FOLDER, folder);
        return this;
    }

    public boolean getUnreadOnly() {
        return Boolean.TRUE.equals(MailParameters.bool(PARAM_UNREAD_ONLY, getRequestParameter(PARAM_UNREAD_ONLY)));
    }

    public PollMailRequest unreadOnly(boolean unreadOnly) {
        setRequestParameter(PARAM_UNREAD_ONLY, unreadOnly);
        return this;
    }

    public String getFrom() {
        return MailParameters.string(PARAM_FROM, getRequestParameter(PARAM_FROM));
    }

    public PollMailRequest from(String from) {
        setRequestParameter(PARAM_FROM, from);
        return this;
    }

    public String getSubject() {
        return MailParameters.string(PARAM_SUBJECT, getRequestParameter(PARAM_SUBJECT));
    }

    public PollMailRequest subject(String subject) {
        setRequestParameter(PARAM_SUBJECT, subject);
        return this;
    }

    public Date getReceivedSince() {
        Object value = getRequestParameter(PARAM_RECEIVED_SINCE);
        if (value == null || value instanceof Date) {
            return (Date) value;
        }
        throw new MailConnectorException("Parameter 'receivedSince' must be a java.util.Date, but was "
                + value.getClass().getName());
    }

    public PollMailRequest receivedSince(Date since) {
        setRequestParameter(PARAM_RECEIVED_SINCE, since);
        return this;
    }

    /** The maximum number of messages, or {@code null} for no limit. */
    public Integer getLimit() {
        return MailParameters.integer(PARAM_LIMIT, getRequestParameter(PARAM_LIMIT));
    }

    public PollMailRequest limit(int limit) {
        setRequestParameter(PARAM_LIMIT, limit);
        return this;
    }

    public boolean getMarkAsRead() {
        return Boolean.TRUE.equals(MailParameters.bool(PARAM_MARK_AS_READ, getRequestParameter(PARAM_MARK_AS_READ)));
    }

    public PollMailRequest markAsRead(boolean markAsRead) {
        setRequestParameter(PARAM_MARK_AS_READ, markAsRead);
        return this;
    }

    public boolean downloadAttachments() {
        Boolean value =
                MailParameters.bool(PARAM_DOWNLOAD_ATTACHMENTS, getRequestParameter(PARAM_DOWNLOAD_ATTACHMENTS));
        if (value == null) {
            value = MailParameters.bool(
                    PARAM_DOWNLOAD_ATTACHMENTS_CAMEL, getRequestParameter(PARAM_DOWNLOAD_ATTACHMENTS_CAMEL));
        }
        return value != null ? value : mailConnector.mailService().settings().downloadAttachments();
    }

    public PollMailRequest downloadAttachments(boolean download) {
        setRequestParameter(PARAM_DOWNLOAD_ATTACHMENTS, download);
        return this;
    }

    @Override
    protected boolean isRequestValid() {
        Integer limit = getLimit();
        if (limit != null && limit < 1) {
            throw new MailConnectorException("mail-poll: parameter 'limit' must be at least 1, but was " + limit);
        }
        getReceivedSince();
        return true;
    }

    @Override
    public String toString() {
        return "PollMailRequest [folder=" + requestParameters.get(PARAM_FOLDER) + "]";
    }
}

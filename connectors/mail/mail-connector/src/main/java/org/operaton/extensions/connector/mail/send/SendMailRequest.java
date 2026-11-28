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
package org.operaton.extensions.connector.mail.send;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.EmptyResponse;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailParameters;

/**
 * Request of the {@code mail-send} connector. Input parameters (all optional except {@code to}, and {@code from} when
 * no default sender is configured):
 *
 * <ul>
 *   <li>{@code from}, {@code fromAlias}, {@code to}, {@code cc}, {@code bcc}, {@code replyTo}: a string
 *       (comma-separated) or a list of strings
 *   <li>{@code subject}, {@code text}, {@code html}: literal content
 *   <li>{@code subjectTemplate}, {@code textTemplate}, {@code htmlTemplate}, {@code templateVariables} (map) and
 *       {@code templateEngine} ({@code simple} or {@code freemarker}): rendered content, instead of the literal one
 *   <li>{@code inReplyTo}: the Message-ID of the mail this one answers (sets In-Reply-To and References)
 *   <li>{@code fileNames}: paths of files to attach; {@code files}: map of file name to {@code byte[]} or {@code
 *       InputStream}; {@code attachments}: list of file values; {@code attachmentVariables}: names of process
 *       variables of type file
 * </ul>
 */
public class SendMailRequest extends AbstractConnectorRequest<EmptyResponse> {

    protected static final String PARAM_FROM = "from";
    protected static final String PARAM_FROM_ALIAS = "fromAlias";
    protected static final String PARAM_TO = "to";
    protected static final String PARAM_CC = "cc";
    protected static final String PARAM_BCC = "bcc";
    protected static final String PARAM_REPLY_TO = "replyTo";
    protected static final String PARAM_SUBJECT = "subject";
    protected static final String PARAM_TEXT = "text";
    protected static final String PARAM_HTML = "html";
    protected static final String PARAM_SUBJECT_TEMPLATE = "subjectTemplate";
    protected static final String PARAM_TEXT_TEMPLATE = "textTemplate";
    protected static final String PARAM_HTML_TEMPLATE = "htmlTemplate";
    protected static final String PARAM_TEMPLATE_VARIABLES = "templateVariables";
    protected static final String PARAM_TEMPLATE_ENGINE = "templateEngine";
    protected static final String PARAM_IN_REPLY_TO = "inReplyTo";
    protected static final String PARAM_FILE_NAMES = "fileNames";
    protected static final String PARAM_FILES = "files";
    protected static final String PARAM_ATTACHMENTS = "attachments";
    protected static final String PARAM_ATTACHMENT_VARIABLES = "attachmentVariables";

    private final AbstractMailConnector<?, ?> mailConnector;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public SendMailRequest(AbstractMailConnector<?, ?> connector) {
        super((Connector) connector);
        this.mailConnector = connector;
    }

    public String getTo() {
        return MailParameters.addressList(PARAM_TO, getRequestParameter(PARAM_TO));
    }

    public SendMailRequest to(String to) {
        setRequestParameter(PARAM_TO, to);
        return this;
    }

    /** The sender: the parameter, else the configured default sender. */
    public String getFrom() {
        String from = MailParameters.addressList(PARAM_FROM, getRequestParameter(PARAM_FROM));
        return from != null ? from : mailConnector.mailService().settings().sender();
    }

    public SendMailRequest from(String from) {
        setRequestParameter(PARAM_FROM, from);
        return this;
    }

    public String getFromAlias() {
        String alias = MailParameters.string(PARAM_FROM_ALIAS, getRequestParameter(PARAM_FROM_ALIAS));
        return alias != null ? alias : mailConnector.mailService().settings().senderAlias();
    }

    public SendMailRequest fromAlias(String alias) {
        setRequestParameter(PARAM_FROM_ALIAS, alias);
        return this;
    }

    public String getCc() {
        return MailParameters.addressList(PARAM_CC, getRequestParameter(PARAM_CC));
    }

    public SendMailRequest cc(String cc) {
        setRequestParameter(PARAM_CC, cc);
        return this;
    }

    public String getBcc() {
        return MailParameters.addressList(PARAM_BCC, getRequestParameter(PARAM_BCC));
    }

    public SendMailRequest bcc(String bcc) {
        setRequestParameter(PARAM_BCC, bcc);
        return this;
    }

    public String getReplyTo() {
        return MailParameters.addressList(PARAM_REPLY_TO, getRequestParameter(PARAM_REPLY_TO));
    }

    public SendMailRequest replyTo(String replyTo) {
        setRequestParameter(PARAM_REPLY_TO, replyTo);
        return this;
    }

    public String getSubject() {
        return MailParameters.string(PARAM_SUBJECT, getRequestParameter(PARAM_SUBJECT));
    }

    public SendMailRequest subject(String subject) {
        setRequestParameter(PARAM_SUBJECT, subject);
        return this;
    }

    public String getText() {
        return MailParameters.string(PARAM_TEXT, getRequestParameter(PARAM_TEXT));
    }

    public SendMailRequest text(String text) {
        setRequestParameter(PARAM_TEXT, text);
        return this;
    }

    public String getHtml() {
        return MailParameters.string(PARAM_HTML, getRequestParameter(PARAM_HTML));
    }

    public SendMailRequest html(String html) {
        setRequestParameter(PARAM_HTML, html);
        return this;
    }

    public String getSubjectTemplate() {
        return MailParameters.string(PARAM_SUBJECT_TEMPLATE, getRequestParameter(PARAM_SUBJECT_TEMPLATE));
    }

    public SendMailRequest subjectTemplate(String template) {
        setRequestParameter(PARAM_SUBJECT_TEMPLATE, template);
        return this;
    }

    public String getTextTemplate() {
        return MailParameters.string(PARAM_TEXT_TEMPLATE, getRequestParameter(PARAM_TEXT_TEMPLATE));
    }

    public SendMailRequest textTemplate(String template) {
        setRequestParameter(PARAM_TEXT_TEMPLATE, template);
        return this;
    }

    public String getHtmlTemplate() {
        return MailParameters.string(PARAM_HTML_TEMPLATE, getRequestParameter(PARAM_HTML_TEMPLATE));
    }

    public SendMailRequest htmlTemplate(String template) {
        setRequestParameter(PARAM_HTML_TEMPLATE, template);
        return this;
    }

    public Map<String, Object> getTemplateVariables() {
        return MailParameters.map(PARAM_TEMPLATE_VARIABLES, getRequestParameter(PARAM_TEMPLATE_VARIABLES));
    }

    public SendMailRequest templateVariables(Map<String, Object> variables) {
        setRequestParameter(PARAM_TEMPLATE_VARIABLES, variables);
        return this;
    }

    public String getTemplateEngine() {
        return MailParameters.string(PARAM_TEMPLATE_ENGINE, getRequestParameter(PARAM_TEMPLATE_ENGINE));
    }

    public SendMailRequest templateEngine(String engine) {
        setRequestParameter(PARAM_TEMPLATE_ENGINE, engine);
        return this;
    }

    public String getInReplyTo() {
        return MailParameters.string(PARAM_IN_REPLY_TO, getRequestParameter(PARAM_IN_REPLY_TO));
    }

    public SendMailRequest inReplyTo(String messageId) {
        setRequestParameter(PARAM_IN_REPLY_TO, messageId);
        return this;
    }

    public List<Object> getFileNames() {
        return MailParameters.list(PARAM_FILE_NAMES, getRequestParameter(PARAM_FILE_NAMES));
    }

    public SendMailRequest fileNames(String... fileNames) {
        setRequestParameter(PARAM_FILE_NAMES, Arrays.asList(fileNames));
        return this;
    }

    public Map<String, Object> getFiles() {
        return MailParameters.map(PARAM_FILES, getRequestParameter(PARAM_FILES));
    }

    public SendMailRequest files(Map<String, ?> files) {
        setRequestParameter(PARAM_FILES, files);
        return this;
    }

    public List<Object> getAttachments() {
        return MailParameters.list(PARAM_ATTACHMENTS, getRequestParameter(PARAM_ATTACHMENTS));
    }

    public SendMailRequest attachments(Object... fileValues) {
        setRequestParameter(PARAM_ATTACHMENTS, Arrays.asList(fileValues));
        return this;
    }

    public List<Object> getAttachmentVariables() {
        return MailParameters.list(PARAM_ATTACHMENT_VARIABLES, getRequestParameter(PARAM_ATTACHMENT_VARIABLES));
    }

    public SendMailRequest attachmentVariables(String... names) {
        setRequestParameter(PARAM_ATTACHMENT_VARIABLES, Arrays.asList(names));
        return this;
    }

    @Override
    protected boolean isRequestValid() {
        if (getTo() == null) {
            throw new MailConnectorException("mail-send: missing parameter 'to'");
        }
        if (getFrom() == null) {
            throw new MailConnectorException(
                    "mail-send: missing parameter 'from' and no default sender (mail.sender) is configured");
        }
        requireOneOf("subject", getSubject(), "subjectTemplate", getSubjectTemplate());
        requireOneOf("text", getText(), "textTemplate", getTextTemplate());
        requireOneOf("html", getHtml(), "htmlTemplate", getHtmlTemplate());
        return true;
    }

    private static void requireOneOf(String literalName, String literal, String templateName, String template) {
        if (literal != null && template != null) {
            throw new MailConnectorException(
                    "mail-send: parameters '" + literalName + "' and '" + templateName + "' are mutually exclusive");
        }
    }

    @Override
    public String toString() {
        return "SendMailRequest [from=" + requestParameters.get(PARAM_FROM) + ", to=" + requestParameters.get(PARAM_TO)
                + ", subject=" + requestParameters.get(PARAM_SUBJECT) + "]";
    }
}

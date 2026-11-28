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

import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.impl.context.BpmnExecutionContext;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.core.instance.CoreExecution;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.connect.spi.ConnectorResponse;
import org.operaton.extensions.connector.mail.AbstractMailConnector;
import org.operaton.extensions.connector.mail.EmptyResponse;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.MailInvocation;
import org.operaton.extensions.connector.mail.service.MailService;
import org.operaton.extensions.connector.mail.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code mail-send} connector: sends a mail over SMTP. Stateless and thread-safe. */
public class SendMailConnector extends AbstractMailConnector<SendMailRequest, EmptyResponse> {

    public static final String CONNECTOR_ID = "mail-send";

    private static final Logger LOG = LoggerFactory.getLogger(SendMailConnector.class);

    public SendMailConnector() {
        super(CONNECTOR_ID);
    }

    @Override
    public SendMailRequest createRequest() {
        return new SendMailRequest(this);
    }

    @Override
    public ConnectorResponse execute(SendMailRequest request) {
        MailService service = mailService();
        try {
            MimeMessage message = createMessage(service, request);
            LOG.debug("send mail with subject '{}' to {}", message.getSubject(), request.getTo());
            new MailInvocation<>(message, request, interceptors(), target -> {
                        service.send(target);
                        return null;
                    })
                    .proceed();
        } catch (MailConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new MailConnectorException("Failed to send mail: " + e.getMessage(), e);
        }
        return new EmptyResponse();
    }

    protected MimeMessage createMessage(MailService service, SendMailRequest request)
            throws MessagingException, IOException {
        MimeMessage message = service.newMessage();
        InternetAddress from = new InternetAddress(request.getFrom(), request.getFromAlias(), "UTF-8");
        from.validate();
        message.setFrom(from);
        message.setRecipients(RecipientType.TO, InternetAddress.parse(request.getTo()));
        if (request.getCc() != null) {
            message.setRecipients(RecipientType.CC, InternetAddress.parse(request.getCc()));
        }
        if (request.getBcc() != null) {
            message.setRecipients(RecipientType.BCC, InternetAddress.parse(request.getBcc()));
        }
        if (request.getReplyTo() != null) {
            message.setReplyTo(InternetAddress.parse(request.getReplyTo()));
        }
        if (request.getInReplyTo() != null) {
            message.setHeader("In-Reply-To", request.getInReplyTo());
            message.setHeader("References", request.getInReplyTo());
        }
        message.setSentDate(new Date());

        TemplateRenderer renderer = TemplateRenderer.forEngine(request.getTemplateEngine());
        Map<String, Object> variables = request.getTemplateVariables();
        String subject = request.getSubjectTemplate() != null
                ? renderer.render(request.getSubjectTemplate(), variables, false)
                : request.getSubject();
        String text = request.getTextTemplate() != null
                ? renderer.render(request.getTextTemplate(), variables, false)
                : request.getText();
        String html = request.getHtmlTemplate() != null
                ? renderer.render(request.getHtmlTemplate(), variables, true)
                : request.getHtml();
        message.setSubject(subject == null ? "" : subject, "UTF-8");

        List<OutgoingAttachment> attachments = collectAttachments(request);
        List<Path> files = new ArrayList<>();
        for (Object name : request.getFileNames()) {
            Path path = Paths.get(String.valueOf(name));
            if (!Files.isRegularFile(path)) {
                throw new MailConnectorException("mail-send: attachment file not found: " + path.toAbsolutePath());
            }
            files.add(path);
        }
        setContent(message, text, html, attachments, files);
        return message;
    }

    private static void setContent(
            MimeMessage message, String text, String html, List<OutgoingAttachment> attachments, List<Path> files)
            throws MessagingException, IOException {
        boolean hasAttachments = !attachments.isEmpty() || !files.isEmpty();
        if (!hasAttachments && html == null) {
            message.setText(text == null ? "" : text, "UTF-8");
            return;
        }
        if (!hasAttachments) {
            message.setContent(bodyOf(text, html));
            return;
        }
        MimeMultipart mixed = new MimeMultipart("mixed");
        if (text != null || html != null) {
            MimeBodyPart body = new MimeBodyPart();
            if (html == null) {
                body.setText(text, "UTF-8");
            } else {
                body.setContent(bodyOf(text, html));
            }
            mixed.addBodyPart(body);
        }
        for (OutgoingAttachment attachment : attachments) {
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(
                    new DataHandler(new ByteArrayDataSource(attachment.content(), attachment.contentType())));
            part.setFileName(attachment.name());
            part.setDisposition(MimeBodyPart.ATTACHMENT);
            mixed.addBodyPart(part);
        }
        for (Path file : files) {
            MimeBodyPart part = new MimeBodyPart();
            part.attachFile(file.toFile());
            mixed.addBodyPart(part);
        }
        message.setContent(mixed);
    }

    /** text and/or html as multipart/alternative (the HTML part last, as clients prefer the last). */
    private static Multipart bodyOf(String text, String html) throws MessagingException {
        MimeMultipart alternative = new MimeMultipart("alternative");
        if (text != null) {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(text, "UTF-8");
            alternative.addBodyPart(textPart);
        }
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=utf-8");
        alternative.addBodyPart(htmlPart);
        return alternative;
    }

    private static List<OutgoingAttachment> collectAttachments(SendMailRequest request) throws IOException {
        List<OutgoingAttachment> result = new ArrayList<>();
        for (Map.Entry<String, Object> file : request.getFiles().entrySet()) {
            result.add(new OutgoingAttachment(file.getKey(), null, bytesOf(file.getKey(), file.getValue())));
        }
        for (Object value : request.getAttachments()) {
            if (value instanceof FileValue fileValue) {
                result.add(fromFileValue(fileValue.getFilename(), fileValue));
            } else {
                throw new MailConnectorException("mail-send: parameter 'attachments' must contain file values, but had "
                        + (value == null ? "null" : value.getClass().getName()));
            }
        }
        for (Object name : request.getAttachmentVariables()) {
            String variable = String.valueOf(name);
            TypedValue typed = variableTyped(variable);
            if (!(typed instanceof FileValue fileValue)) {
                throw new MailConnectorException("mail-send: process variable '" + variable
                        + "' (attachmentVariables) is not a file value but "
                        + (typed == null ? "missing" : typed.getType().getName()));
            }
            String filename = fileValue.getFilename() != null ? fileValue.getFilename() : variable;
            result.add(fromFileValue(filename, fileValue));
        }
        return result;
    }

    private static TypedValue variableTyped(String name) {
        BpmnExecutionContext context;
        try {
            context = Context.getBpmnExecutionContext();
        } catch (IllegalStateException e) {
            // Operaton 2.2 throws when no execution is active, older versions return null
            context = null;
        }
        if (context == null) {
            throw new MailConnectorException(
                    "mail-send: 'attachmentVariables' can only be used inside a process instance");
        }
        CoreExecution execution = context.getExecution();
        return execution.getVariableTyped(name);
    }

    private static OutgoingAttachment fromFileValue(String filename, FileValue value) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new MailConnectorException("mail-send: a file value used as attachment has no file name");
        }
        try (InputStream in = value.getValue()) {
            return new OutgoingAttachment(filename, value.getMimeType(), in.readAllBytes());
        }
    }

    private static byte[] bytesOf(String name, Object value) throws IOException {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof InputStream in) {
            try (in) {
                return in.readAllBytes();
            }
        }
        throw new MailConnectorException("mail-send: parameter 'files' entry '" + name
                + "' must be a byte[] or an InputStream, but was "
                + (value == null ? "null" : value.getClass().getName()));
    }
}

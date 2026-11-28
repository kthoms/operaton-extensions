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

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** A received mail, detached from the mail server (serializable, safe to keep as a process variable). */
public class Mail implements Serializable {

    private static final long serialVersionUID = 2L;

    private String from;
    private String fromAddress;
    private String to;
    private String cc;
    private String subject;
    private Date sentDate;
    private Date receivedDate;
    private int messageNumber;
    private String messageId;
    private String text;
    private String html;
    private final List<Attachment> attachments = new ArrayList<>();

    public String getFrom() {
        return from;
    }

    /** The bare address of the first sender (no display name), or {@code null}. */
    public String getFromAddress() {
        return fromAddress;
    }

    public String getTo() {
        return to;
    }

    public String getCc() {
        return cc;
    }

    public String getSubject() {
        return subject;
    }

    public Date getSentDate() {
        return sentDate;
    }

    public Date getReceivedDate() {
        return receivedDate;
    }

    public int getMessageNumber() {
        return messageNumber;
    }

    public String getMessageId() {
        return messageId;
    }

    /** The plain text body, or {@code null}. */
    public String getText() {
        return text;
    }

    /** The HTML body, or {@code null}. */
    public String getHtml() {
        return html;
    }

    public List<Attachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    /** Reads a message without downloading or keeping attachments (metadata only). */
    public static Mail from(Message message) throws MessagingException, IOException {
        return from(message, null, false);
    }

    /**
     * Reads a message.
     *
     * @param downloadDir when not {@code null}, attachments are written to a new unique sub-directory of it
     * @param keepContent keep the attachment bytes in memory ({@link Attachment#getContent()})
     */
    public static Mail from(Message message, Path downloadDir, boolean keepContent)
            throws MessagingException, IOException {
        Mail mail = new Mail();
        jakarta.mail.Address[] senders = message.getFrom();
        mail.from = senders == null ? null : InternetAddress.toString(senders);
        if (senders != null && senders.length > 0 && senders[0] instanceof InternetAddress address) {
            mail.fromAddress = address.getAddress();
        }
        mail.to = addresses(message, RecipientType.TO);
        mail.cc = addresses(message, RecipientType.CC);
        mail.subject = message.getSubject();
        mail.sentDate = message.getSentDate();
        mail.receivedDate = message.getReceivedDate();
        mail.messageNumber = message.getMessageNumber();
        if (message instanceof MimeMessage mime) {
            mail.messageId = mime.getMessageID();
        }
        List<Part> attachmentParts = new ArrayList<>();
        collect(message, mail, attachmentParts);
        Path dir = null;
        if (downloadDir != null && !attachmentParts.isEmpty()) {
            dir = Files.createDirectories(downloadDir.resolve(UUID.randomUUID().toString()));
        }
        for (Part part : attachmentParts) {
            mail.attachments.add(readAttachment(part, dir, keepContent));
        }
        return mail;
    }

    private static String addresses(Message message, RecipientType type) throws MessagingException {
        jakarta.mail.Address[] recipients = message.getRecipients(type);
        return recipients == null ? null : InternetAddress.toString(recipients);
    }

    private static void collect(Part part, Mail mail, List<Part> attachmentParts)
            throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, mail, attachmentParts);
            }
            return;
        }
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (part.getFileName() != null && !part.isMimeType("text/plain") && !part.isMimeType("text/html"));
        if (attachment) {
            attachmentParts.add(part);
        } else if (part.isMimeType("text/plain")) {
            if (mail.text == null) {
                mail.text = (String) part.getContent();
            }
        } else if (part.isMimeType("text/html")) {
            if (mail.html == null) {
                mail.html = (String) part.getContent();
            }
        }
    }

    private static Attachment readAttachment(Part part, Path dir, boolean keepContent)
            throws MessagingException, IOException {
        String name = safeFileName(part.getFileName());
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            bytes = out.toByteArray();
        }
        Attachment attachment = new Attachment(name, contentTypeOf(part), bytes.length, keepContent ? bytes : null);
        if (dir != null) {
            Path file = dir.resolve(name);
            Files.write(file, bytes);
            attachment.downloaded(file);
        }
        return attachment;
    }

    private static String contentTypeOf(Part part) throws MessagingException {
        String type = part.getContentType();
        if (type == null) {
            return "application/octet-stream";
        }
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The attachment's file name without any directory part: names come from untrusted senders. */
    static String safeFileName(String raw) {
        String decoded = raw;
        if (raw != null) {
            try {
                decoded = MimeUtility.decodeText(raw);
            } catch (java.io.UnsupportedEncodingException e) {
                decoded = raw;
            }
        }
        if (decoded == null || decoded.isBlank()) {
            return "attachment";
        }
        String name = decoded.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .trim();
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "attachment" : name;
    }

    @Override
    public String toString() {
        return "Mail [from=" + from + ", to=" + to + ", cc=" + cc + ", subject=" + subject + ", sentDate=" + sentDate
                + ", receivedDate=" + receivedDate + ", messageNumber=" + messageNumber + ", messageId=" + messageId
                + ", attachments=" + attachments + "]";
    }
}

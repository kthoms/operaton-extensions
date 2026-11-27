/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.inbound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.extensions.connector.mail.dto.Attachment;
import org.operaton.extensions.connector.mail.dto.Mail;

/**
 * The process variables created from a received mail. With the default prefix {@code mail}: {@code mailFrom} (display
 * form), {@code mailFromAddress}, {@code mailTo}, {@code mailCc}, {@code mailSubject}, {@code mailText}, {@code
 * mailHtml}, {@code mailMessageId}, {@code mailReceivedDate}, {@code mailAttachmentCount} and one file variable
 * {@code mailAttachment1}, {@code mailAttachment2}, ... per attachment. Variables without a value are not set.
 */
public final class MailVariables {

    private MailVariables() {}

    public static Map<String, Object> from(Mail mail, String prefix) {
        Map<String, Object> variables = new HashMap<>();
        put(variables, prefix + "From", mail.getFrom());
        put(variables, prefix + "FromAddress", mail.getFromAddress());
        put(variables, prefix + "To", mail.getTo());
        put(variables, prefix + "Cc", mail.getCc());
        put(variables, prefix + "Subject", mail.getSubject());
        put(variables, prefix + "Text", mail.getText());
        put(variables, prefix + "Html", mail.getHtml());
        put(variables, prefix + "MessageId", mail.getMessageId());
        put(variables, prefix + "ReceivedDate", mail.getReceivedDate());
        List<Attachment> attachments = mail.getAttachments();
        variables.put(prefix + "AttachmentCount", attachments.size());
        for (int i = 0; i < attachments.size(); i++) {
            Attachment attachment = attachments.get(i);
            FileValue file = Variables.fileValue(attachment.getFileName())
                    .file(attachment.getContent())
                    .mimeType(attachment.getContentType())
                    .create();
            variables.put(prefix + "Attachment" + (i + 1), file);
        }
        return variables;
    }

    private static void put(Map<String, Object> variables, String name, Object value) {
        if (value != null) {
            variables.put(name, value);
        }
    }
}

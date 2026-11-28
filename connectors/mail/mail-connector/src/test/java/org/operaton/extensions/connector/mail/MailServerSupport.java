/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;

/** An in-JVM SMTP + IMAP server (GreenMail) with the account {@code test@localhost}. */
public final class MailServerSupport implements AutoCloseable {

    public static final String USER = "test@localhost";
    public static final String PASSWORD = "secret";

    private final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP_IMAP);

    public MailServerSupport() {
        greenMail.start();
        greenMail.setUser(USER, PASSWORD);
    }

    public GreenMail greenMail() {
        return greenMail;
    }

    /** Puts a mail directly into the INBOX of {@code test@localhost} (as if received). */
    public MimeMessage deliver(String from, String subject, String text) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setRecipient(RecipientType.TO, new InternetAddress(USER));
        message.setSubject(subject);
        message.setText(text);
        message.saveChanges();
        greenMail.getUserManager().getUserByEmail(USER).deliver(message);
        return message;
    }

    /** Delivers a mail with one attachment. */
    public MimeMessage deliverWithAttachment(
            String from, String subject, String text, String fileName, String contentType, byte[] content)
            throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setRecipient(RecipientType.TO, new InternetAddress(USER));
        message.setSubject(subject);
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText(text);
        multipart.addBodyPart(body);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(
                new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(content, contentType)));
        attachment.setFileName(fileName);
        attachment.setDisposition(MimeBodyPart.ATTACHMENT);
        multipart.addBodyPart(attachment);
        message.setContent(multipart);
        message.saveChanges();
        greenMail.getUserManager().getUserByEmail(USER).deliver(message);
        return message;
    }

    @Override
    public void close() {
        greenMail.stop();
    }
}

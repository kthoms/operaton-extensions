/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.inbound;

import jakarta.mail.Flags;
import jakarta.mail.Flags.Flag;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.SearchTerm;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder;
import org.operaton.extensions.connector.mail.MailConnectorException;
import org.operaton.extensions.connector.mail.dto.Mail;
import org.operaton.extensions.connector.mail.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls an IMAP folder for unread mails and starts or correlates processes from them according to the {@link
 * InboundSettings#routes() routes}.
 *
 * <p>Delivery is at-least-once: a mail is completed (marked read, deleted or moved) only after its process was started
 * or its message correlated, so a crash in between leads to a duplicate on the next poll. The variable {@code
 * <prefix>MessageId} lets the process detect duplicates.
 *
 * <p>Mails matching no route are left untouched. A mail whose processing fails is flagged (IMAP {@code \Flagged}), logged
 * at ERROR and not retried; clear the flag to retry it.
 *
 * <p>Thread-safe: {@link #pollOnce()} calls are serialized. {@link #start()} runs it periodically on one daemon
 * thread; {@link #close()} stops it.
 */
public final class MailInboundPoller implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MailInboundPoller.class);

    private final MailService mailService;
    private final InboundSettings settings;
    private final RuntimeService runtimeService;
    private final ReentrantLock pollLock = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private ScheduledExecutorService executor;

    public MailInboundPoller(MailService mailService, InboundSettings settings, RuntimeService runtimeService) {
        this.mailService = Objects.requireNonNull(mailService, "mailService");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
    }

    /** Starts polling every {@link InboundSettings#pollInterval()}, the first poll immediately. */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "operaton-mail-inbound");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::pollQuietly, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Mail inbound poller started: folder '{}', every {}", folderName(), settings.pollInterval());
    }

    public boolean isRunning() {
        return started.get();
    }

    /** Number of mails routed successfully since creation. */
    public long processedCount() {
        return processed.get();
    }

    /** Number of mails whose processing failed since creation. */
    public long failedCount() {
        return failed.get();
    }

    /** Stops polling; waits for a running poll to finish. */
    @Override
    public synchronized void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("Mail inbound poller stopped");
    }

    private void pollQuietly() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // the schedule must survive a mail server outage; the failure is reported at ERROR
            LOG.error("Mail inbound poll of folder '{}' failed: {}", folderName(), e.getMessage(), e);
        }
    }

    /**
     * Polls once.
     *
     * @return the number of mails routed successfully
     * @throws MailConnectorException if the mail server cannot be reached or the folder cannot be read
     */
    public int pollOnce() {
        pollLock.lock();
        try {
            boolean expunge = settings.completion() != InboundSettings.Completion.MARK_SEEN;
            return mailService.withFolder(folderName(), true, expunge, this::process);
        } finally {
            pollLock.unlock();
        }
    }

    private String folderName() {
        return settings.folder() != null
                ? settings.folder()
                : mailService.settings().pollFolder();
    }

    private int process(Folder folder) throws MessagingException {
        SearchTerm pending = new AndTerm(new SearchTerm[] {
            new FlagTerm(new Flags(Flag.SEEN), false),
            new FlagTerm(new Flags(Flag.FLAGGED), false),
            new FlagTerm(new Flags(Flag.DELETED), false)
        });
        List<Message> messages = new ArrayList<>(Arrays.asList(folder.search(pending)));
        messages.sort(Comparator.comparingInt(Message::getMessageNumber));
        int routed = 0;
        int handled = 0;
        for (Message message : messages) {
            if (handled >= settings.batchSize()) {
                break;
            }
            try {
                if (route(folder, message)) {
                    routed++;
                    handled++;
                }
            } catch (Exception e) {
                handled++;
                failed.incrementAndGet();
                LOG.error(
                        "Mail inbound: processing of mail {} failed, flagging it: {}",
                        describe(message),
                        e.getMessage(),
                        e);
                flag(message);
            }
        }
        return routed;
    }

    private boolean route(Folder folder, Message message) throws Exception {
        String subject = message.getSubject();
        String fromAddress = fromAddressOf(message);
        MailRoute route = settings.routes().stream()
                .filter(candidate -> candidate.matches(fromAddress, subject))
                .findFirst()
                .orElse(null);
        if (route == null) {
            LOG.debug("Mail inbound: no route for mail {}, left untouched", describe(message));
            return false;
        }
        Mail mail = Mail.from(message, (Path) null, true);
        String businessKey = route.businessKey(subject);
        Map<String, Object> variables = MailVariables.from(mail, settings.variablePrefix());
        dispatch(route, businessKey, variables);
        complete(folder, message);
        processed.incrementAndGet();
        LOG.debug("Mail inbound: route '{}' handled mail {}", route.name(), describe(message));
        return true;
    }

    private void dispatch(MailRoute route, String businessKey, Map<String, Object> variables) {
        if (businessKey != null && route.messageName() != null) {
            runtimeService
                    .createMessageCorrelation(route.messageName())
                    .processInstanceBusinessKey(businessKey)
                    .setVariables(variables)
                    .correlateWithResult();
        } else if (route.processDefinitionKey() != null) {
            runtimeService.startProcessInstanceByKey(route.processDefinitionKey(), businessKey, variables);
        } else {
            MessageCorrelationBuilder builder =
                    runtimeService.createMessageCorrelation(route.messageName()).setVariables(variables);
            builder.correlateStartMessage();
        }
    }

    private void complete(Folder folder, Message message) throws MessagingException {
        switch (settings.completion()) {
            case MARK_SEEN -> message.setFlag(Flag.SEEN, true);
            case DELETE -> message.setFlag(Flag.DELETED, true);
            case MOVE -> {
                Folder target = folder.getStore().getFolder(settings.processedFolder());
                if (!target.exists() && !target.create(Folder.HOLDS_MESSAGES)) {
                    throw new MailConnectorException(
                            "Cannot create the processed folder '" + settings.processedFolder() + "'");
                }
                folder.copyMessages(new Message[] {message}, target);
                message.setFlag(Flag.DELETED, true);
            }
        }
    }

    private static void flag(Message message) {
        try {
            message.setFlag(Flag.FLAGGED, true);
        } catch (MessagingException e) {
            LOG.error("Mail inbound: could not flag the failed mail {}: {}", describe(message), e.getMessage(), e);
        }
    }

    private static String fromAddressOf(Message message) throws MessagingException {
        jakarta.mail.Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof jakarta.mail.internet.InternetAddress address) {
            return address.getAddress();
        }
        return null;
    }

    private static String describe(Message message) {
        try {
            return "#" + message.getMessageNumber() + " '" + message.getSubject() + "'";
        } catch (MessagingException e) {
            return "#" + message.getMessageNumber();
        }
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.inbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.operaton.extensions.connector.mail.MailConnectorException;

/** Settings of the {@link MailInboundPoller}. Immutable. */
public final class InboundSettings {

    /** What happens to a mail after its route was executed successfully. */
    public enum Completion {
        /** Mark as read (default). */
        MARK_SEEN,
        /** Delete from the folder. */
        DELETE,
        /** Move to {@code processedFolder} (created if missing). */
        MOVE
    }

    public static final String DEFAULT_VARIABLE_PREFIX = "mail";

    private final String folder;
    private final Duration pollInterval;
    private final Completion completion;
    private final String processedFolder;
    private final String variablePrefix;
    private final int batchSize;
    private final List<MailRoute> routes;

    private InboundSettings(Builder b) {
        this.folder = b.folder;
        this.pollInterval = b.pollInterval;
        this.completion = b.completion;
        this.processedFolder = b.processedFolder;
        this.variablePrefix = b.variablePrefix;
        this.batchSize = b.batchSize;
        this.routes = List.copyOf(b.routes);
        if (routes.isEmpty()) {
            throw new MailConnectorException("Invalid inbound mail settings: at least one route is required");
        }
        Set<String> names = new HashSet<>();
        for (MailRoute route : routes) {
            if (!names.add(route.name())) {
                throw new MailConnectorException(
                        "Invalid inbound mail settings: duplicate route name '" + route.name() + "'");
            }
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new MailConnectorException("Invalid inbound mail settings: pollInterval must be positive");
        }
        if (batchSize < 1) {
            throw new MailConnectorException("Invalid inbound mail settings: batchSize must be at least 1");
        }
        if (completion == Completion.MOVE && (processedFolder == null || processedFolder.isBlank())) {
            throw new MailConnectorException("Invalid inbound mail settings: completion MOVE needs processedFolder");
        }
        if (variablePrefix == null || !variablePrefix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new MailConnectorException(
                    "Invalid inbound mail settings: variablePrefix must be a valid identifier, but was '"
                            + variablePrefix + "'");
        }
    }

    /** The folder to watch, or {@code null} for the configured poll folder. */
    public String folder() {
        return folder;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public Completion completion() {
        return completion;
    }

    public String processedFolder() {
        return processedFolder;
    }

    public String variablePrefix() {
        return variablePrefix;
    }

    public int batchSize() {
        return batchSize;
    }

    public List<MailRoute> routes() {
        return routes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds {@link InboundSettings}; {@link #build()} validates. */
    public static final class Builder {

        private String folder;
        private Duration pollInterval = Duration.ofSeconds(30);
        private Completion completion = Completion.MARK_SEEN;
        private String processedFolder;
        private String variablePrefix = DEFAULT_VARIABLE_PREFIX;
        private int batchSize = 50;
        private final List<MailRoute> routes = new ArrayList<>();

        private Builder() {}

        public Builder folder(String folder) {
            this.folder = folder;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder completion(Completion completion) {
            this.completion = completion;
            return this;
        }

        public Builder processedFolder(String processedFolder) {
            this.processedFolder = processedFolder;
            return this;
        }

        public Builder variablePrefix(String variablePrefix) {
            this.variablePrefix = variablePrefix;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder route(MailRoute route) {
            this.routes.add(route);
            return this;
        }

        public InboundSettings build() {
            return new InboundSettings(this);
        }
    }
}

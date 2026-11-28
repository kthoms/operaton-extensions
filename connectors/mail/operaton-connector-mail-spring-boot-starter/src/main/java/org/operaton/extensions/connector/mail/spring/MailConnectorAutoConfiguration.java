/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.spring;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.extensions.connector.mail.MailConnectorPlugin;
import org.operaton.extensions.connector.mail.config.MailSettings;
import org.operaton.extensions.connector.mail.inbound.InboundSettings;
import org.operaton.extensions.connector.mail.inbound.MailInboundPoller;
import org.operaton.extensions.connector.mail.inbound.MailRoute;
import org.operaton.extensions.connector.mail.service.MailService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the mail connectors' configuration as an engine plugin and, when {@code inbound.enabled=true}, the inbound
 * poller. Invalid configuration stops the application at startup.
 */
@AutoConfiguration
@ConditionalOnClass(MailConnectorPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.connector.mail", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MailConnectorProperties.class)
public class MailConnectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailService operatonMailService(MailConnectorProperties p) {
        return new MailService(settings(p));
    }

    @Bean
    @ConditionalOnMissingBean
    public MailConnectorPlugin operatonMailConnectorPlugin(MailService mailService) {
        return new MailConnectorPlugin(mailService);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "operaton.extensions.connector.mail.inbound",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnMissingBean
    public MailInboundPoller operatonMailInboundPoller(
            MailService mailService, MailConnectorProperties p, RuntimeService runtimeService) {
        MailConnectorProperties.Inbound in = p.getInbound();
        InboundSettings.Builder builder = InboundSettings.builder()
                .folder(in.getFolder())
                .pollInterval(in.getPollInterval())
                .completion(in.getCompletion())
                .processedFolder(in.getProcessedFolder())
                .variablePrefix(in.getVariablePrefix())
                .batchSize(in.getBatchSize());
        for (MailConnectorProperties.Route r : in.getRoutes()) {
            builder.route(MailRoute.builder(r.getName())
                    .fromPattern(r.getFromPattern())
                    .subjectPattern(r.getSubjectPattern())
                    .businessKeyPattern(r.getBusinessKeyPattern())
                    .processDefinitionKey(r.getProcessDefinitionKey())
                    .messageName(r.getMessageName())
                    .build());
        }
        return new MailInboundPoller(mailService, builder.build(), runtimeService);
    }

    static MailSettings settings(MailConnectorProperties p) {
        if (p.getConfig() != null && !p.getConfig().isBlank()) {
            return MailSettings.load(p.getConfig());
        }
        MailSettings.Builder b = MailSettings.builder()
                .smtp(
                        p.getSmtp().getHost(),
                        p.getSmtp().getPort(),
                        p.getSmtp().getTls(),
                        p.getSmtp().getUsername(),
                        p.getSmtp().getPassword())
                .imap(
                        p.getImap().getHost(),
                        p.getImap().getPort(),
                        p.getImap().getTls(),
                        p.getImap().getUsername(),
                        p.getImap().getPassword())
                .sender(p.getSender())
                .senderAlias(p.getSenderAlias())
                .pollFolder(p.getFolder())
                .downloadAttachments(p.isDownloadAttachments())
                .attachmentPath(p.getAttachmentPath());
        p.getProperties().forEach(b::property);
        return b.build();
    }
}

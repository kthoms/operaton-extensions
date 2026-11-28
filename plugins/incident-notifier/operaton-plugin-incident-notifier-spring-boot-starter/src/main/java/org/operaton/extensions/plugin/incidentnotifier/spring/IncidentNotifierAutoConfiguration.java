/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier.spring;

import java.util.Map;
import org.operaton.extensions.plugin.incidentnotifier.IncidentNotifierPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link IncidentNotifierPlugin} as a bean; the Operaton Spring Boot starter adds every
 * {@code ProcessEnginePlugin} bean to the engine. Without a configured channel the engine fails to
 * start; set {@code operaton.extensions.plugin.incident-notifier.enabled=false} to switch it off.
 */
@AutoConfiguration
@ConditionalOnClass(IncidentNotifierPlugin.class)
@ConditionalOnProperty(
        prefix = "operaton.extensions.plugin.incident-notifier",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IncidentNotifierProperties.class)
public class IncidentNotifierAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public IncidentNotifierPlugin incidentNotifierPlugin(IncidentNotifierProperties p) {
        IncidentNotifierPlugin plugin = new IncidentNotifierPlugin();
        plugin.setIncidentTypes(String.join(",", p.getIncidentTypes()));
        plugin.setThrottleMillis(p.getThrottle().toMillis());
        plugin.setCockpitBaseUrl(p.getCockpitBaseUrl());
        plugin.setMaxIncidentsPerMessage(p.getMaxIncidentsPerMessage());
        plugin.setMaxBufferedIncidents(p.getMaxBufferedIncidents());
        plugin.setSenderThreads(p.getSenderThreads());
        plugin.setTimeZone(p.getTimeZone());
        plugin.setRecipientVariable(p.getRecipientVariable());
        plugin.setCcVariable(p.getCcVariable());
        if (p.getTemplate().getSubject() != null) {
            plugin.setSubject(p.getTemplate().getSubject());
        }
        if (p.getTemplate().getBody() != null) {
            plugin.setBodyTemplate(p.getTemplate().getBody());
        }
        if (p.getTemplate().getIncident() != null) {
            plugin.setIncidentTemplate(p.getTemplate().getIncident());
        }
        IncidentNotifierProperties.Email e = p.getEmail();
        plugin.setMailHost(e.getHost());
        plugin.setMailPort(e.getPort());
        plugin.setMailUsername(e.getUsername());
        plugin.setMailPassword(e.getPassword());
        plugin.setMailFrom(e.getFrom());
        plugin.setMailTo(e.getTo());
        plugin.setMailCc(e.getCc());
        plugin.setMailStartTls(e.isStarttls());
        plugin.setMailStartTlsRequired(e.isStarttlsRequired());
        plugin.setMailSsl(e.isSsl());
        plugin.setMailTimeoutMillis((int) e.getTimeout().toMillis());
        plugin.setWebhookUrl(p.getWebhook().getUrl());
        plugin.setWebhookHeaders(Map.copyOf(p.getWebhook().getHeaders()));
        plugin.setWebhookTimeoutMillis((int) p.getWebhook().getTimeout().toMillis());
        plugin.setSlackWebhookUrl(p.getSlack().getUrl());
        plugin.setTeamsWebhookUrl(p.getTeams().getUrl());
        return plugin;
    }
}

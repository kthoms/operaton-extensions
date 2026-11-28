/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.connector.mail.config.MailSettings;
import org.operaton.extensions.connector.mail.service.MailService;

/**
 * Engine plugin that gives the {@code mail-send}, {@code mail-poll} and {@code mail-delete} connectors their
 * configuration for one engine. Without it the connectors read {@code mail-config.properties} (see {@link
 * MailSettings#loadDefault()}).
 *
 * <p>The plugin owns the {@link MailService}; the connectors find the plugin among the plugins of the engine that
 * executes them, so several engines in one JVM can use different mail accounts. It requires the Operaton Connect engine plugin ({@code ConnectProcessEnginePlugin}) to be registered as
 * well. Instances are thread-safe once configured.
 */
public class MailConnectorPlugin extends AbstractProcessEnginePlugin {

    private MailService mailService;
    private MailSettings settings;
    private String configLocation;

    public MailConnectorPlugin() {}

    public MailConnectorPlugin(MailService mailService) {
        this.mailService = mailService;
    }

    /** Settings built in code. */
    public void setSettings(MailSettings settings) {
        this.settings = settings;
    }

    /** Location of a properties file: {@code classpath:name} or a file path. */
    public void setConfigLocation(String configLocation) {
        this.configLocation = configLocation;
    }

    /** The service the connectors use, available after configuration. */
    public MailService getMailService() {
        return mailService;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (mailService == null) {
            MailSettings resolved = settings;
            if (resolved == null) {
                resolved = configLocation != null ? MailSettings.load(configLocation) : MailSettings.loadDefault();
            }
            mailService = new MailService(resolved);
        }
    }
}

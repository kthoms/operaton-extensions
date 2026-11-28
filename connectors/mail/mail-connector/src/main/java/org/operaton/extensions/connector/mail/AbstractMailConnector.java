/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.connect.impl.AbstractConnector;
import org.operaton.connect.spi.ConnectorRequest;
import org.operaton.connect.spi.ConnectorRequestInterceptor;
import org.operaton.connect.spi.ConnectorResponse;
import org.operaton.extensions.connector.mail.config.MailSettings;
import org.operaton.extensions.connector.mail.service.MailService;

/**
 * Base of the mail connectors. Connect keeps one connector instance per JVM, so it must not hold per-engine state:
 * the {@link MailService} comes from the engine executing the request ({@link MailConnectorPlugin}) and otherwise from
 * the default properties file, loaded once on first use.
 */
public abstract class AbstractMailConnector<Q extends ConnectorRequest<R>, R extends ConnectorResponse>
        extends AbstractConnector<Q, R> {

    private final Object fallbackLock = new Object();
    private volatile MailService fallback;

    protected AbstractMailConnector(String connectorId) {
        super(connectorId);
    }

    /** The mail service to use for the request being executed. Fails if nothing is configured. */
    public MailService mailService() {
        ProcessEngineConfigurationImpl configuration = activeConfiguration();
        if (configuration != null) {
            MailService registered = find(configuration.getProcessEnginePlugins());
            if (registered != null) {
                return registered;
            }
        }
        MailService service = fallback;
        if (service == null) {
            synchronized (fallbackLock) {
                service = fallback;
                if (service == null) {
                    service = new MailService(MailSettings.loadDefault());
                    fallback = service;
                }
            }
        }
        return service;
    }

    /** Looks through the plugins, including those grouped by a composite plugin (Spring Boot does that). */
    private static MailService find(List<ProcessEnginePlugin> plugins) {
        if (plugins == null) {
            return null;
        }
        for (ProcessEnginePlugin plugin : plugins) {
            if (plugin instanceof MailConnectorPlugin mailPlugin && mailPlugin.getMailService() != null) {
                return mailPlugin.getMailService();
            }
            if (plugin instanceof CompositeProcessEnginePlugin composite) {
                MailService nested = find(composite.getPlugins());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** The engine executing the current thread, or {@code null}; Operaton 2.2 throws instead of returning null. */
    private static ProcessEngineConfigurationImpl activeConfiguration() {
        try {
            return Context.getProcessEngineConfiguration();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** A snapshot of the interceptors, so one execution is not affected by concurrent changes. */
    protected List<ConnectorRequestInterceptor> interceptors() {
        return new ArrayList<>(requestInterceptors);
    }
}

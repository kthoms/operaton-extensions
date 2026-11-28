/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

/** The plugin refuses to start with an invalid configuration. */
class PluginConfigurationTest {

    private static void preInit(Consumer<IncidentNotifierPlugin> configure) {
        IncidentNotifierPlugin plugin = new IncidentNotifierPlugin();
        configure.accept(plugin);
        ProcessEngineConfigurationImpl cfg = new StandaloneInMemProcessEngineConfiguration();
        plugin.preInit(cfg);
    }

    @Test
    void noChannelFails() {
        assertThatThrownBy(() -> preInit(p -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no channel configured");
    }

    @Test
    void negativeThrottleFails() {
        assertThatThrownBy(() -> preInit(p -> {
                    p.setWebhookUrl("http://h.example/x");
                    p.setThrottleMillis(-1);
                }))
                .hasMessageContaining("throttleMillis");
    }

    @Test
    void invalidWebhookUrlFails() {
        assertThatThrownBy(() -> preInit(p -> p.setSlackWebhookUrl("ftp://h.example/x")))
                .hasMessageContaining("slackWebhookUrl");
        assertThatThrownBy(() -> preInit(p -> p.setTeamsWebhookUrl("not a url")))
                .hasMessageContaining("teamsWebhookUrl");
    }

    @Test
    void incompleteMailConfigurationFails() {
        assertThatThrownBy(() -> preInit(p -> p.setMailHost("smtp.example.org")))
                .hasMessageContaining("mailFrom and mailTo");
        assertThatThrownBy(() -> preInit(p -> {
                    p.setWebhookUrl("http://h.example/x");
                    p.setMailTo("a@example.org");
                }))
                .hasMessageContaining("mailHost");
    }

    @Test
    void unknownPlaceholderFails() {
        assertThatThrownBy(() -> preInit(p -> {
                    p.setWebhookUrl("http://h.example/x");
                    p.setSubject("Hello @WHO");
                }))
                .hasMessageContaining("@WHO");
    }

    @Test
    void badTimeZoneFails() {
        assertThatThrownBy(() -> preInit(p -> {
                    p.setWebhookUrl("http://h.example/x");
                    p.setTimeZone("Mars/Base");
                }))
                .hasMessageContaining("timeZone");
    }

    @Test
    void unknownIncidentTypeFailsWhenTheEngineIsInitialised() {
        IncidentNotifierPlugin plugin = new IncidentNotifierPlugin();
        plugin.setWebhookUrl("http://h.example/x");
        plugin.setIncidentTypes("doesNotExist");
        ProcessEngineConfigurationImpl cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:cfg-test-" + System.nanoTime());
        cfg.getProcessEnginePlugins().add(plugin);

        assertThatThrownBy(cfg::buildProcessEngine).hasMessageContaining("doesNotExist");
    }

    @Test
    void aPluginInstanceServesOneEngineOnly() {
        IncidentNotifierPlugin plugin = new IncidentNotifierPlugin();
        plugin.setWebhookUrl("http://h.example/x");
        plugin.preInit(new StandaloneInMemProcessEngineConfiguration());

        assertThatThrownBy(() -> plugin.preInit(new StandaloneInMemProcessEngineConfiguration()))
                .isInstanceOf(IllegalStateException.class);
    }
}

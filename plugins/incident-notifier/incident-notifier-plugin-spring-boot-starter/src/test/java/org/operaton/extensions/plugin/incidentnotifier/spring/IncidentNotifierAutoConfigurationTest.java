/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.incidentnotifier.IncidentNotifierPlugin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IncidentNotifierAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IncidentNotifierAutoConfiguration.class));

    @Test
    void registersThePluginWithTheBoundProperties() {
        runner.withPropertyValues(
                        "operaton.extensions.plugin.incident-notifier.throttle=5s",
                        "operaton.extensions.plugin.incident-notifier.cockpit-base-url=http://cockpit:8080/operaton",
                        "operaton.extensions.plugin.incident-notifier.incident-types=failedJob",
                        "operaton.extensions.plugin.incident-notifier.email.host=smtp.example.org",
                        "operaton.extensions.plugin.incident-notifier.email.port=2525",
                        "operaton.extensions.plugin.incident-notifier.email.from=ops@example.org",
                        "operaton.extensions.plugin.incident-notifier.email.to=team@example.org",
                        "operaton.extensions.plugin.incident-notifier.webhook.url=http://hooks.example.org/x",
                        "operaton.extensions.plugin.incident-notifier.webhook.headers.Authorization=Bearer t")
                .run(context -> {
                    assertThat(context).hasSingleBean(IncidentNotifierPlugin.class);
                    IncidentNotifierPlugin plugin = context.getBean(IncidentNotifierPlugin.class);
                    assertThat(plugin.getThrottleMillis()).isEqualTo(5000);
                    assertThat(plugin.getCockpitBaseUrl()).isEqualTo("http://cockpit:8080/operaton");
                    assertThat(plugin.getIncidentTypes()).isEqualTo("failedJob");
                    assertThat(plugin.getMailHost()).isEqualTo("smtp.example.org");
                    assertThat(plugin.getMailPort()).isEqualTo(2525);
                    assertThat(plugin.getMailTo()).isEqualTo("team@example.org");
                    assertThat(plugin.getWebhookHeaders()).containsEntry("Authorization", "Bearer t");
                });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("operaton.extensions.plugin.incident-notifier.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IncidentNotifierPlugin.class));
    }

    @Test
    void backsOffForAUserDefinedPlugin() {
        IncidentNotifierPlugin own = new IncidentNotifierPlugin();
        runner.withBean(IncidentNotifierPlugin.class, () -> own)
                .run(context -> assertThat(context.getBean(IncidentNotifierPlugin.class))
                        .isSameAs(own));
    }
}

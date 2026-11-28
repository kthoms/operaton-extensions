/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

/** With {@code authorizationCheckEnabled=false} the directory is readable by every authenticated user. */
class ScimAuthorizationCheckDisabledEngineTest {

    static final FakeScimServer SCIM = Scim.seededServer();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                cfg.setAuthorizationEnabled(true);
                ScimIdentityProviderPlugin plugin = Scim.plugin(SCIM);
                plugin.setAuthorizationCheckEnabled(false);
                cfg.getProcessEnginePlugins().add(plugin);
            })
            .build();

    @AfterAll
    static void stopServer() {
        SCIM.close();
    }

    @AfterEach
    void clear() {
        engine.getIdentityService().clearAuthentication();
    }

    @Test
    void anUnprivilegedUserSeesTheWholeDirectory() {
        engine.getIdentityService().setAuthentication("alice", java.util.List.of("approvers"));

        assertThat(engine.getIdentityService().createUserQuery().count()).isEqualTo(4);
        assertThat(engine.getIdentityService().createGroupQuery().count()).isEqualTo(4);
    }
}

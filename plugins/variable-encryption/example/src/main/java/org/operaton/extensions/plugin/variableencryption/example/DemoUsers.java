/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the demo users: alice is a clerk (group clerks) who reviews applications. */
@Component
public class DemoUsers implements ApplicationRunner {

    private final IdentityService identityService;

    public DemoUsers(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (identityService.createGroupQuery().groupId("clerks").count() == 0) {
            Group clerks = identityService.newGroup("clerks");
            clerks.setName("Clerks");
            clerks.setType("WORKFLOW");
            identityService.saveGroup(clerks);
        }
        if (identityService.createUserQuery().userId("alice").count() == 0) {
            User alice = identityService.newUser("alice");
            alice.setFirstName("Alice");
            alice.setLastName("Clerk");
            alice.setPassword("alice");
            identityService.saveUser(alice);
        }
        if (identityService
                        .createUserQuery()
                        .userId("alice")
                        .memberOfGroup("clerks")
                        .count()
                == 0) {
            identityService.createMembership("alice", "clerks");
        }
    }
}

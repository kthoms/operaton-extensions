/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.example;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the example's users once: alice works in first-level support (groups {@code support} and
 * {@code billing}), bob leads the team and takes escalations ({@code leads}). Password = user id.
 */
@Component
public class SeedUsers {

    private final IdentityService identityService;

    public SeedUsers(IdentityService identityService) {
        this.identityService = identityService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        group("support", "Support");
        group("billing", "Billing");
        group("leads", "Team leads");
        user("alice", "Alice", "support", "billing");
        user("bob", "Bob", "leads");
    }

    private void group(String id, String name) {
        if (identityService.createGroupQuery().groupId(id).count() == 0) {
            Group group = identityService.newGroup(id);
            group.setName(name);
            group.setType("WORKFLOW");
            identityService.saveGroup(group);
        }
    }

    private void user(String id, String firstName, String... groups) {
        if (identityService.createUserQuery().userId(id).count() == 0) {
            User user = identityService.newUser(id);
            user.setFirstName(firstName);
            user.setLastName("Example");
            user.setPassword(id);
            identityService.saveUser(user);
            for (String group : groups) {
                identityService.createMembership(id, group);
            }
        }
    }
}

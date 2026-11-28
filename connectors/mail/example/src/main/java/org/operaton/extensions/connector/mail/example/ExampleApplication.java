/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.example;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Support desk: incoming mail starts a ticket process, an agent answers, the customer gets the reply by mail. */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }

    /** Seeds the agents alice and bob (password = user name) and their group. */
    @Bean
    ApplicationRunner seedAgents(IdentityService identityService) {
        return args -> {
            if (identityService.createGroupQuery().groupId("agents").count() == 0) {
                Group group = identityService.newGroup("agents");
                group.setName("Support agents");
                group.setType("WORKFLOW");
                identityService.saveGroup(group);
            }
            for (String name : new String[] {"alice", "bob"}) {
                if (identityService.createUserQuery().userId(name).count() == 0) {
                    User user = identityService.newUser(name);
                    user.setFirstName(name);
                    user.setLastName("Agent");
                    user.setPassword(name);
                    identityService.saveUser(user);
                    identityService.createMembership(name, "agents");
                }
            }
        };
    }
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.example;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.identity.User;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the demo users: alice submits documents, bob reviews them (group reviewers). */
@Component
public class DemoUsers implements ApplicationRunner {

    private final IdentityService identityService;

    public DemoUsers(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (identityService.createGroupQuery().groupId("reviewers").count() == 0) {
            Group reviewers = identityService.newGroup("reviewers");
            reviewers.setName("Reviewers");
            reviewers.setType("WORKFLOW");
            identityService.saveGroup(reviewers);
        }
        createUser("alice", "Alice", "Author");
        createUser("bob", "Bob", "Reviewer");
        if (identityService
                        .createUserQuery()
                        .userId("bob")
                        .memberOfGroup("reviewers")
                        .count()
                == 0) {
            identityService.createMembership("bob", "reviewers");
        }
    }

    private void createUser(String id, String first, String last) {
        if (identityService.createUserQuery().userId(id).count() == 0) {
            User user = identityService.newUser(id);
            user.setFirstName(first);
            user.setLastName(last);
            user.setPassword(id);
            identityService.saveUser(user);
        }
    }
}

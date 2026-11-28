/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.example;

import static org.operaton.bpm.engine.authorization.Authorization.ANY;
import static org.operaton.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.operaton.bpm.engine.authorization.Permissions.ACCESS;
import static org.operaton.bpm.engine.authorization.Permissions.CREATE;
import static org.operaton.bpm.engine.authorization.Permissions.CREATE_INSTANCE;
import static org.operaton.bpm.engine.authorization.Permissions.READ;
import static org.operaton.bpm.engine.authorization.Permissions.UPDATE;
import static org.operaton.bpm.engine.authorization.Resources.APPLICATION;
import static org.operaton.bpm.engine.authorization.Resources.GROUP;
import static org.operaton.bpm.engine.authorization.Resources.PROCESS_DEFINITION;
import static org.operaton.bpm.engine.authorization.Resources.PROCESS_INSTANCE;
import static org.operaton.bpm.engine.authorization.Resources.TASK;
import static org.operaton.bpm.engine.authorization.Resources.USER;

import org.operaton.bpm.engine.AuthorizationService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.authorization.Authorization;
import org.operaton.bpm.engine.authorization.Permission;
import org.operaton.bpm.engine.authorization.Resource;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.plugin.AdministratorAuthorizationPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authorizations for the SCIM groups. Users and groups are not stored in the engine, but
 * authorizations are: they are granted to the <em>ids</em> of SCIM groups and apply to whoever the
 * SCIM server reports as a member.
 *
 * <ul>
 *   <li>{@code operaton-admin}: everything (the engine's administrator group).
 *   <li>{@code approvers} and {@code finance}: work on tasks and start the expense process, see the
 *       other users and groups in Tasklist.
 * </ul>
 */
@Configuration
public class ExpenseAuthorizations {

    @Bean
    AdministratorAuthorizationPlugin administrators() {
        AdministratorAuthorizationPlugin plugin = new AdministratorAuthorizationPlugin();
        plugin.setAdministratorGroupName("operaton-admin");
        return plugin;
    }

    @Bean
    AbstractProcessEnginePlugin expenseGroupAuthorizations() {
        return new AbstractProcessEnginePlugin() {
            @Override
            public void postProcessEngineBuild(ProcessEngine engine) {
                for (String group : new String[] {"approvers", "finance"}) {
                    grant(engine.getAuthorizationService(), group, APPLICATION, "tasklist", ACCESS);
                    grant(engine.getAuthorizationService(), group, TASK, ANY, READ, UPDATE);
                    grant(
                            engine.getAuthorizationService(),
                            group,
                            PROCESS_DEFINITION,
                            "expense-approval",
                            READ,
                            CREATE_INSTANCE);
                    grant(engine.getAuthorizationService(), group, PROCESS_INSTANCE, ANY, CREATE);
                    grant(engine.getAuthorizationService(), group, USER, ANY, READ);
                    grant(engine.getAuthorizationService(), group, GROUP, ANY, READ);
                }
            }
        };
    }

    private static void grant(
            AuthorizationService authorizations,
            String group,
            Resource resource,
            String resourceId,
            Permission... permissions) {
        boolean exists = authorizations
                        .createAuthorizationQuery()
                        .groupIdIn(group)
                        .resourceType(resource)
                        .resourceId(resourceId)
                        .count()
                > 0;
        if (exists) {
            return;
        }
        Authorization authorization = authorizations.createNewAuthorization(AUTH_TYPE_GRANT);
        authorization.setGroupId(group);
        authorization.setResource(resource);
        authorization.setResourceId(resourceId);
        for (Permission permission : permissions) {
            authorization.addPermission(permission);
        }
        authorizations.saveAuthorization(authorization);
    }
}

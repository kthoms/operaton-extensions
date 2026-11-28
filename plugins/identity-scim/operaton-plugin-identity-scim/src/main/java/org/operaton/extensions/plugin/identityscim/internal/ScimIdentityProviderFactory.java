/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimIdentityProviderFactory.java (Apache-2.0)
 * Changes: read-only only; the client and cache live in the directory shared by all sessions
 * instead of being created per session.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import org.operaton.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.operaton.extensions.plugin.identityscim.ScimSettings;

/** Opens a {@link ScimIdentityProviderSession} per engine command. Thread-safe and immutable. */
public final class ScimIdentityProviderFactory implements SessionFactory {

    private final ScimSettings settings;
    private final ScimDirectory directory;
    private final ScimPasswordChecker passwordChecker;

    /**
     * Creates the factory.
     *
     * @param settings the validated settings
     * @param directory the shared directory
     * @param passwordChecker answers password checks
     */
    public ScimIdentityProviderFactory(
            ScimSettings settings, ScimDirectory directory, ScimPasswordChecker passwordChecker) {
        this.settings = settings;
        this.directory = directory;
        this.passwordChecker = passwordChecker;
    }

    @Override
    public Class<?> getSessionType() {
        return ReadOnlyIdentityProvider.class;
    }

    @Override
    public Session openSession() {
        return new ScimIdentityProviderSession(settings, directory, passwordChecker);
    }
}

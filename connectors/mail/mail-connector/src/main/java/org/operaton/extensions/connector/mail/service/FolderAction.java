/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.service;

import jakarta.mail.Folder;

/** Work done on an open mail folder. The folder is closed when it returns. */
@FunctionalInterface
public interface FolderAction<T> {

    T apply(Folder folder) throws Exception;
}

/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.delegate;

import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.internal.S3Action;

/** Lists objects below a prefix ({@code s3-list}). Parameters and results: see {@link AbstractS3Delegate} and the README. */
public class S3ListDelegate extends AbstractS3Delegate {

    /** Finds the stores through the engine that runs the task. */
    public S3ListDelegate() {
        super(S3Action.LIST, null);
    }

    public S3ListDelegate(S3StoreRegistry registry) {
        super(S3Action.LIST, registry);
    }
}

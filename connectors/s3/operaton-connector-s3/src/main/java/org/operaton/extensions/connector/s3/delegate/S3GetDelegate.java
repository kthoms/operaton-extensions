/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.delegate;

import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.internal.S3Action;

/** Downloads an object into a file variable ({@code s3-get}). Parameters and results: see {@link AbstractS3Delegate} and the README. */
public class S3GetDelegate extends AbstractS3Delegate {

    /** Finds the stores through the engine that runs the task. */
    public S3GetDelegate() {
        super(S3Action.GET, null);
    }

    public S3GetDelegate(S3StoreRegistry registry) {
        super(S3Action.GET, registry);
    }
}

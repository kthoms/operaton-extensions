/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.delegate;

import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.internal.S3Action;

/** Deletes an object ({@code s3-delete}). Parameters and results: see {@link AbstractS3Delegate} and the README. */
public class S3DeleteDelegate extends AbstractS3Delegate {

    /** Finds the stores through the engine that runs the task. */
    public S3DeleteDelegate() {
        super(S3Action.DELETE, null);
    }

    public S3DeleteDelegate(S3StoreRegistry registry) {
        super(S3Action.DELETE, registry);
    }
}

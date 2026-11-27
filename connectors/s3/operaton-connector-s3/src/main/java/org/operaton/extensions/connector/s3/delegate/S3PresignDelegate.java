/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.delegate;

import org.operaton.extensions.connector.s3.S3StoreRegistry;
import org.operaton.extensions.connector.s3.internal.S3Action;

/** Creates a presigned URL ({@code s3-presign}). Parameters and results: see {@link AbstractS3Delegate} and the README. */
public class S3PresignDelegate extends AbstractS3Delegate {

    /** Finds the stores through the engine that runs the task. */
    public S3PresignDelegate() {
        super(S3Action.PRESIGN, null);
    }

    public S3PresignDelegate(S3StoreRegistry registry) {
        super(S3Action.PRESIGN, registry);
    }
}

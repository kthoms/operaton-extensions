/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing;

import io.opentelemetry.api.common.AttributeKey;

/** Names of the span attributes the plugin sets. Immutable constants; safe to use from any thread. */
public final class OperatonTracingAttributes {

    public static final AttributeKey<String> PROCESS_DEFINITION_KEY =
            AttributeKey.stringKey("operaton.process.definition.key");

    public static final AttributeKey<String> PROCESS_DEFINITION_ID =
            AttributeKey.stringKey("operaton.process.definition.id");

    public static final AttributeKey<String> PROCESS_INSTANCE_ID =
            AttributeKey.stringKey("operaton.process.instance.id");

    public static final AttributeKey<String> BUSINESS_KEY = AttributeKey.stringKey("operaton.process.business_key");

    public static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("operaton.tenant.id");

    public static final AttributeKey<String> ACTIVITY_ID = AttributeKey.stringKey("operaton.activity.id");

    public static final AttributeKey<String> ACTIVITY_NAME = AttributeKey.stringKey("operaton.activity.name");

    public static final AttributeKey<String> ACTIVITY_TYPE = AttributeKey.stringKey("operaton.activity.type");

    public static final AttributeKey<String> ACTIVITY_INSTANCE_ID =
            AttributeKey.stringKey("operaton.activity.instance.id");

    public static final AttributeKey<String> JOB_ID = AttributeKey.stringKey("operaton.job.id");

    /** The job handler type, for example {@code async-continuation} or {@code timer-intermediate-transition}. */
    public static final AttributeKey<String> JOB_TYPE = AttributeKey.stringKey("operaton.job.type");

    /** {@code message} or {@code timer}. */
    public static final AttributeKey<String> JOB_CATEGORY = AttributeKey.stringKey("operaton.job.category");

    public static final AttributeKey<Long> JOB_RETRIES = AttributeKey.longKey("operaton.job.retries");

    public static final AttributeKey<String> EXTERNAL_TASK_ID = AttributeKey.stringKey("operaton.external_task.id");

    public static final AttributeKey<String> EXTERNAL_TASK_TOPIC =
            AttributeKey.stringKey("operaton.external_task.topic");

    public static final AttributeKey<String> EXTERNAL_TASK_WORKER_ID =
            AttributeKey.stringKey("operaton.external_task.worker_id");

    /** The reason a process instance or activity instance was cancelled, when it ended by cancellation. */
    public static final AttributeKey<String> CANCEL_REASON = AttributeKey.stringKey("operaton.cancel.reason");

    /** {@code true} on a span that could not be started when the work began (restart, other cluster node). */
    public static final AttributeKey<Boolean> START_MISSING = AttributeKey.booleanKey("operaton.span.start_missing");

    /** {@code true} on a span that was closed early because the open-span limit was reached. */
    public static final AttributeKey<Boolean> EVICTED = AttributeKey.booleanKey("operaton.span.evicted");

    private OperatonTracingAttributes() {}
}

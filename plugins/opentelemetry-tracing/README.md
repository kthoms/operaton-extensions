# OpenTelemetry Tracing

Creates OpenTelemetry spans for process instances, activities, jobs and external tasks and propagates the trace context through the process instance.

## When to use

Use it when you run Operaton next to other traced services (OpenTelemetry, Jaeger, Tempo, any OTLP backend) and want
to see where time goes inside a process: which activity is slow, which job failed, how an order moved through
the engine, an asynchronous job and an external task worker, all in one trace.

Do not use it for engine metrics (use a metrics plugin) or as an audit log (use history). The plugin needs only the
OpenTelemetry **API**; the application must provide the SDK and the exporter. Without an SDK all spans are no-ops.

## Installation

Spring Boot:

```xml
<dependency>
  <groupId>org.operaton.plugin.opentelemetry-tracing</groupId>
  <artifactId>opentelemetry-tracing-plugin-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
<!-- your SDK and exporter, for example -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

The starter registers the plugin as a `ProcessEnginePlugin` bean. It uses the application's `OpenTelemetry` bean, or
`GlobalOpenTelemetry` (for example set by the Java agent) when there is none.

Plain engine:

```xml
<dependency>
  <groupId>org.operaton.plugin.opentelemetry-tracing</groupId>
  <artifactId>opentelemetry-tracing-plugin</artifactId>
  <version>VERSION</version>
</dependency>
```

```java
OpenTelemetryTracingPlugin plugin = new OpenTelemetryTracingPlugin();
plugin.setOpenTelemetry(openTelemetry);
configuration.getProcessEnginePlugins().add(plugin);
```

Shared engine / distribution: put the plugin jar and `opentelemetry-api` (plus SDK) on the engine class path (for
example `lib/`) and add `org.operaton.extensions.plugin.opentelemetrytracing.OpenTelemetryTracingPlugin` as a plugin in
`bpm-platform.xml`.

## Usage

Nothing changes in the models. Starting a process, working on a user task or running a job produces this hierarchy:

```
process order-fulfilment                      (instance start to end)
  serviceTask validateOrder
  userTask approveOrder
  job async-continuation                      (job execution)
    serviceTask chargePayment
    serviceTask shipOrder
      external-task fetch ship-order          (fetchAndLock command)
      external-task complete ship-order       (complete command)
  noneEndEvent orderFulfilled
```

Rules:

- **Incoming context.** A process instance started while a span is current (an HTTP server span, a Kafka consumer span)
  becomes its child. A called process becomes a child of its call activity.
- **Continuation.** At start the W3C `traceparent` of the process span is stored in the process variable `_traceparent`.
  Jobs, user task completion and the external task API call commands read it, so they continue the same trace even
  on another node or after a restart.
- **Current span in your code.** While an activity runs, its span is current, so spans created by instrumented
  clients inside a delegate (HTTP, JDBC) are children of the activity. A job span is current for everything the job runs.
- **Attributes.** Process definition key and id, process instance id, business key, tenant, activity id, name, type and
  instance id, job id, type, category and retries, external task id, topic and worker id. Names are constants in
  `OperatonTracingAttributes`.
- **Failures.** An exception that fails a job or command is recorded on the job span (`ERROR`). Spans opened by a
  command that rolls back are closed with `operaton.cancel.reason = transaction rolled back`.
- **External task commands** (`fetchAndLock`, `complete`, `handleFailure`, `handleBpmnError`, `extendLock`) are traced where the
  engine API is called. If a span is already current (a REST server span in your own endpoint) it becomes the parent
  and the process span is added as a link; the worker's own processing time is outside the engine and is not measured.

## Configuration

Plugin properties (setters of `OpenTelemetryTracingPlugin`) and, in the starter, the same names in kebab-case under
`operaton.extensions.plugin.opentelemetry-tracing.*`:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` (starter only) | boolean | `true` | Register the plugin. |
| `openTelemetry` (plugin only) | `OpenTelemetry` | `GlobalOpenTelemetry.get()` | The OpenTelemetry instance. The starter injects the `OpenTelemetry` bean. |
| `instrumentationScopeName` | string | `org.operaton.extensions.opentelemetry-tracing` | Instrumentation scope of the spans. |
| `traceparentVariable` | string | `_traceparent` | Process variable holding the `traceparent`. |
| `storeTraceparent` | boolean | `true` | Store the variable. When `false`, jobs and later commands only continue the trace while the process span is open in the same JVM. |
| `traceProcessInstances` | boolean | `true` | Span per process instance. |
| `traceActivities` | boolean | `true` | Span per activity instance. |
| `traceGateways` | boolean | `false` | Include gateways in activity spans. |
| `traceJobs` | boolean | `true` | Span per job execution. |
| `traceExternalTasks` | boolean | `true` | Spans for the external task engine API commands. |
| `includeBusinessKey` | boolean | `true` | Add the business key attribute (turn off if keys are personal data). |
| `maxOpenSpans` | int | `10000` | Bound of spans held open for running instances; the oldest is ended early and flagged `operaton.span.evicted`. |

Invalid values (`maxOpenSpans < 1`, a blank `traceparentVariable`) stop the engine from starting.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): an order fulfilment process (validation, approval, asynchronous payment, external task shipping) in a Spring Boot application, traced to Jaeger over OTLP.

```bash
cd example
docker compose up -d --wait
../../../mvnw spring-boot:run     # Jaeger UI: http://localhost:16686
../../../mvnw verify              # Testcontainers: PostgreSQL and Jaeger, asserts the span hierarchy
```

## Development

```bash
./mvnw -B -pl plugins/opentelemetry-tracing -amd verify
./mvnw -B -pl plugins/opentelemetry-tracing/opentelemetry-tracing-plugin -am verify -Doperaton.version=2.0.0
```

Tests use `opentelemetry-sdk-testing`'s `InMemorySpanExporter` with an embedded engine (H2); the example IT uses Testcontainers (PostgreSQL, Jaeger). On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

Design notes:

- Fresh implementation, no code ported. Depends on `opentelemetry-api` 1.62 (`provided`); no SDK dependency.
- `BpmnParseListener` adds start (built-in, so it runs before model listeners) and end execution listeners to the process and to every flow node. `ProcessEnginePlugin` also installs two command interceptors: an outer one that scopes bookkeeping to a command, and one inside the command context that creates job and external task spans.
- Spans of running instances are kept in memory, keyed by process or activity instance id (bounded by `maxOpenSpans`). An end that finds no open span (restart, another cluster node) records a short span flagged `operaton.span.start_missing`, parented through the stored `traceparent`. The open span that was lost is never exported, so such spans show a missing parent in the backend.
- Ends are applied only after the transaction commits; a rollback restores or closes the spans so that the registry never leaks.
- Current spans (scopes) are closed when the command ends, never left on a job executor thread.
- Reads `jobId`, `externalTaskId` and `workerId` from engine command classes by reflection (protected fields without getters); the plugin refuses to start if a field is missing in the engine version.
- Guesses: gateways are off by default; the process span covers the whole instance lifetime (long-running instances keep a span open in memory); `traceparent` is a visible process variable (set `storeTraceparent=false` to avoid it); a listener running outside the command chain fails with an `IllegalStateException` rather than being ignored.
- Known limitations: no `tracestate`/baggage propagation; worker time of external tasks and traces of the REST API are not covered; engine commands on a different engine in the same JVM need their own plugin instance. The example README links the BPMN source; a rendered PNG is not included (no renderer was available).

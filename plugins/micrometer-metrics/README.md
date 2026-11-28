# Micrometer Metrics Plugin

Exposes Operaton engine metrics, job backlog, incidents and process instance counters through Micrometer.

The plugin turns what an Operaton engine knows about itself into Micrometer meters, so that any
registry (Prometheus, OTLP, Datadog, ...) can export them: the engine's built-in metrics, the job
backlog, open incidents, running process instances per definition, and counters for process
instance and incident lifecycle events.

## When to use

- You run Operaton and want its health in Prometheus/Grafana (or any Micrometer backend): is the job
  backlog growing, are incidents piling up, how many instances of which process are running, how
  fast are processes started and finished.
- You use Spring Boot and Actuator already: the starter wires everything from properties.

Not for you if you only need the built-in database metrics of Cockpit (`ACT_RU_METRICS`): those stay
available and are unaffected. The plugin does not trace individual instances; for per-instance
telemetry use a tracing plugin. High-cardinality data (process instance ids, business keys) is
deliberately never used as a tag.

## Installation

**Spring Boot** (Actuator and a Micrometer registry, e.g. `micrometer-registry-prometheus`, on the classpath):

```xml
<dependency>
  <groupId>org.operaton.plugin.micrometer-metrics</groupId>
  <artifactId>micrometer-metrics-plugin-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

The starter registers the plugin when a `MeterRegistry` bean exists. Operaton's Spring Boot starter adds
every `ProcessEnginePlugin` bean to the engine.

**Plain engine** (add `io.micrometer:micrometer-core` yourself; the plugin takes it as `provided`):

```xml
<dependency>
  <groupId>org.operaton.plugin.micrometer-metrics</groupId>
  <artifactId>micrometer-metrics-plugin</artifactId>
  <version>VERSION</version>
</dependency>
```

```java
ProcessEngineConfigurationImpl configuration = ...;
configuration.getProcessEnginePlugins().add(new MicrometerMetricsPlugin(meterRegistry));
```

**Shared engine / distribution**: put the jar and `micrometer-core` on the engine's classpath (e.g. `lib/`) and register
the plugin in `bpm-platform.xml` / `processes.xml`; the registry has to be set from code
(`setMeterRegistry`), so use a small `ProcessEnginePlugin` subclass or a Spring-managed engine there.

## Usage

Enable the plugin and scrape the registry. With Spring Boot and Prometheus:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

then `GET /actuator/prometheus`. Meters (Prometheus names in brackets; every meter has the tag `engine`):

| Meter | Type | Tags | Source |
|---|---|---|---|
| `operaton.engine.<metric>` e.g. `operaton.engine.activity.instance.start`, `.job.successful`, `.job.acquisition.attempt`, `.executed.decision.elements` | counter | | the engine's built-in metrics (`MetricsRegistry`), since node start |
| `operaton.process.instances.started` | counter | `process_definition_key` | history event |
| `operaton.process.instances.ended` | counter | `process_definition_key`, `state` (`completed`, `externally_terminated`, `internally_terminated`) | history event |
| `operaton.incidents.raised` | counter | `incident_type`, `process_definition_key` | history event |
| `operaton.incidents.resolved` | counter | same | history event |
| `operaton.incidents.deleted` | counter | same | history event |
| `operaton.jobs.due` | gauge | | polled: executable jobs (retries left, due) |
| `operaton.jobs.failed` | gauge | | polled: jobs without retries left |
| `operaton.incidents.open` | gauge | `incident_type` (configured types + `other`) | polled |
| `operaton.process.instances.running` | gauge | `process_definition_key` | polled, off by default |
| `operaton.metrics.poll.errors` | counter | `poller` (`jobs`, `incidents`, `instances`) | failed poll cycles |
| `operaton.metrics.poll.last.success.seconds` | gauge | `poller` | epoch seconds of the last good poll |

How the pieces work:

- **Engine metrics**: the engine's database meters are wrapped so that a never-cleared total sits next to the engine's
  own counter. Counters are per node and start at zero when the node starts (Prometheus `rate()` handles that);
  the engine's reporting into `ACT_RU_METRICS` is unchanged. They need `metricsEnabled=true` (the engine default); otherwise a warning is
  logged and the `operaton.engine.*` meters are skipped.
- **History counters**: a counting `HistoryEventHandler` runs next to the engine's own handler (composite, it replaces
  nothing) and increments after the transaction committed, so rolled-back work is not counted. Process instance
  events need history level `activity` or higher, incident events need `full`; the plugin warns at startup
  when the level hides them. Call-activity sub-processes count as process instances.
- **Polled gauges** are filled by one daemon thread every `poll-interval`; a scrape reads cached values and never queries the
  database. A failing cycle keeps the previous values, increments `operaton.metrics.poll.errors` and logs a warning;
  the first cycle runs while the engine starts and fails it loudly. Running instances per definition use the
  process definition statistics query, which is expensive on large runtime tables, so it is off by default.
- **Cardinality guards**: `process_definition_key` admits `max-process-definition-keys` distinct values and
  `incident_type` admits `max-incident-types`; later values are reported as `other` (one warning is logged).
- Prometheus reserves the `_created` suffix, which is why incidents are `raised`, not `created`.

## Configuration

Plugin properties (setters on `MicrometerMetricsPlugin`); with the starter the same names under
`operaton.extensions.plugin.micrometer-metrics.*` in kebab-case.

| Plugin property | Starter property | Type | Default | Meaning |
|---|---|---|---|---|
| `meterRegistry` | (the `MeterRegistry` bean) | `MeterRegistry` | required | where meters are registered |
| | `enabled` | boolean | `true` | register the plugin at all |
| `engineMetricsEnabled` | `engine-metrics-enabled` | boolean | `true` | `operaton.engine.*` counters |
| `historyCountersEnabled` | `history-counters-enabled` | boolean | `true` | started/ended/incident counters |
| `jobBacklogEnabled` | `job-backlog-enabled` | boolean | `true` | poll `operaton.jobs.due` / `.failed` |
| `incidentsEnabled` | `incidents-enabled` | boolean | `true` | poll `operaton.incidents.open` |
| `runningInstancesEnabled` | `running-instances-enabled` | boolean | `false` | poll `operaton.process.instances.running` (expensive) |
| `pollIntervalSeconds` | `poll-interval` (duration) | long / `Duration` | 30 s | time between poll cycles; whole seconds, at least 1 |
| `maxProcessDefinitionKeys` | `max-process-definition-keys` | int | `100` | limit of `process_definition_key` values |
| `maxIncidentTypes` | `max-incident-types` | int | `20` | limit of `incident_type` values |
| `incidentTypes` | `incident-types` | list | `failedJob`, `failedExternalTask` | incident types counted individually by `operaton.incidents.open`, the rest is `other` |

Invalid values (no registry, interval below one second, limits below one, blank incident types) stop the engine from starting.
Use one plugin instance per engine and unique engine names per registry.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

The starter is built against Spring Boot 4.1 (the one of Operaton 2.2); its auto-configuration is ordered after the
registry auto-configurations of Spring Boot 3 and 4.

## Example

[`example/`](example/): an order fulfillment process on Spring Boot with PostgreSQL, whose metrics are scraped by
Prometheus and shown on a provisioned Grafana dashboard. `docker compose up -d --wait`, `../../../mvnw spring-boot:run`,
Prometheus at http://localhost:9090, Grafana at http://localhost:3000 (admin/admin); `../../../mvnw verify` runs the IT that
checks the metrics on `/actuator/prometheus` after running orders.

## Development

```bash
./mvnw -B -pl plugins/micrometer-metrics -amd verify     # Docker needed for the example IT
./mvnw -B -pl plugins/micrometer-metrics/micrometer-metrics-plugin -am verify -Doperaton.version=2.1.6
```

Design notes and decisions (best guesses, made without asking):

- Source of the idea: StephenOTT/camunda-prometheus-process-engine-plugin (MIT, archived, Groovy). The code is written fresh in
  Java; nothing is ported, so there is no NOTICE entry.
- Engine metrics come from an in-process wrapper of the engine's `Meter`s (engine internals `MetricsRegistry`/`Meter`) rather than from
  `ManagementService.createMetricsQuery()`, which only sees values flushed every 60 s and, in a cluster, sums over all nodes.
  Counters are therefore per node. Not covered: task metrics (`unique-task-workers`, `task-users`) and diagnostics meters, which the
  engine does not keep as `Meter`s.
- The history handler is added by wrapping the engine's configured handler in `postInit`, which works with the default
  handler and with an explicitly configured one.
- Incident types for `operaton.incidents.open` are a configured list plus `other` (total minus listed), because the incident query has no group-by.
- Engine-specific `engine` tag instead of a per-meter registration of the engine name in the meter name.
- Known limitations: counters reset when the node restarts; no tenant tag (tenants would multiply cardinality); the plugin removes
  its meters on `close()` or within one poll interval after the engine is closed, but not for engines that were never registered
  in `ProcessEngines`; verified with Operaton 2.0.0, 2.1.6 and 2.2.2 only (1.x not tried).

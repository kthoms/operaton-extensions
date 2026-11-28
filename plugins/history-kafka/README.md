# History Kafka Plugin

Publishes Operaton history events to Apache Kafka after the engine transaction commits.

## When to use

Use it to stream what happens in your processes (process and activity instances, tasks,
variables, incidents, jobs, user operations, ...) to analytics, audit or integration consumers
without polling the history tables. Events are published only for **committed** transactions;
a rolled-back command publishes nothing.

Not for: driving process logic from Kafka (use a Kafka connector), or exactly-once pipelines.
The Kafka send happens *after* the database commit and cannot be part of it:

| Setup | Guarantee |
|---|---|
| `send-mode: async` (default) | at-most-once: a crash or broker outage between commit and send loses the events of that transaction; delivery errors are logged and counted, they cannot reach the caller. |
| `send-mode: sync` + `failure-policy: fail` | the caller gets a `HistoryKafkaException` when Kafka does not acknowledge within `send-timeout`. The business transaction **is already committed** (it cannot be rolled back); retrying it may publish duplicates (every message has a unique `id` for de-duplication): at-least-once for callers that retry. Blocks the engine thread while waiting. |
| `failure-policy: log-and-count` | failures are logged at `ERROR` and counted (`getFailedCount()`); nothing is thrown. |

History events are **deltas of the history rows**: an `end` event carries the end time but not the
start time. Consumers join `start` and `end` events (the example does).

Variable values are not published by default (they may contain personal data); stack traces
(`exceptionStacktrace`, `errorDetails`) are never published. Nested data (decision inputs/outputs)
is not included.

## Installation

**Spring Boot** (Operaton Spring Boot starter 2.x):

```xml
<dependency>
  <groupId>org.operaton.plugin.history-kafka</groupId>
  <artifactId>history-kafka-plugin-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

**Plain engine**: add `history-kafka-plugin` (the starter is optional) and register the plugin:

```java
HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
plugin.setBootstrapServers("broker:9092");
configuration.getProcessEnginePlugins().add(plugin);
```

or in `operaton.cfg.xml`:

```xml
<property name="processEnginePlugins"><list>
  <bean class="org.operaton.extensions.plugin.historykafka.HistoryKafkaPlugin">
    <property name="bootstrapServers" value="broker:9092"/>
  </bean>
</list></property>
```

**Shared engine / distribution**: put the jar plus `kafka-clients` and its dependencies into the
container's `lib/` and add the plugin class and properties to the `process-engine` element of
`bpm-platform.xml`. Jackson comes from the engine (the dependency is `provided`).

## Usage

Nothing changes in the models. Enable the plugin and every history event of the configured history
level (`full` by default) that passes the filter is published after commit. Message:

```json
{"id":"7f1c...","type":"process-instance.start","category":"process-instance","event":"start",
 "time":"2026-11-27T06:57:44.88Z","source":"urn:operaton:engine:default",
 "data":{"processInstanceId":"...","processDefinitionKey":"loan-application","startTime":"..."}}
```

* **Key**: process instance id (root process instance id, then event id, when there is none), so
  all events of an instance are ordered on one partition.
* **Topic**: `topic-mapping` (`category.event`, then `category`), else the single `topic`, else
  `topic-prefix` + category (`operaton.history.process-instance`).
* **Categories**: `process-instance`, `activity-instance`, `task-instance`, `variable-instance`,
  `form-property`, `incident`, `job-log`, `external-task-log`, `identity-link-log`,
  `user-operation-log`, `decision-instance`, `batch` (plus the legacy `case-instance`,
  `case-activity-instance`). Event names are Operaton's (`start`, `update`, `end`, `create`, `complete`, ...).
* **Headers**: `content-type`, `operaton-event-type`.
* **CloudEvents** (`format: cloudevents`): structured JSON, `specversion` 1.0, `type`
  `org.operaton.history.<category>.<event>`, `subject` = key, content type `application/cloudevents+json`.
* With `keep-database-handler: true` (default) the history tables are written as before.
* Do not combine with an explicit `historyEventHandler` in the engine configuration: the plugin
  refuses to start then.
* Topics must exist or be auto-created by the broker; the plugin does not create them.

## Configuration

Spring Boot prefix `operaton.extensions.plugin.history-kafka.`; the plugin's setters have the
same names in camelCase (`producer` is `producerProperties`, `send-timeout` is `sendTimeoutMillis`).
Invalid values (unknown category, bad topic name, ...) stop startup.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Register the plugin (Spring Boot only). |
| `bootstrap-servers` | none, required | Kafka `bootstrap.servers` (or set it in `producer`). |
| `topic` | none | One topic for all events. |
| `topic-prefix` | `operaton.history.` | Prefix of per-category topics. |
| `topic-mapping` | empty | Map `category` or `category.event` to a topic. |
| `event-types` | empty (all) | Categories or `category.event` to publish, e.g. `process-instance`, `task-instance.complete`. |
| `format` | `json` | `json` or `cloudevents`. |
| `cloud-events-source` | `urn:operaton:engine:<engine name>` | `source` attribute. |
| `failure-policy` | `fail` | `fail` or `log-and-count`. |
| `send-mode` | `async` | `async` or `sync`. |
| `send-timeout` | `10s` | Sync wait per transaction; default of the producer's `max.block.ms`. |
| `keep-database-handler` | `true` | `false` replaces the database history handler (history tables stay empty). |
| `include-variable-values` | `false` | Include variable values in `variable-instance` events. |
| `producer.*` | | Kafka producer properties, passed through. Defaults: `acks=all`, `enable.idempotence=true`, String serializers (not overridable). |

Plugin API extras: `setProducer(Producer<String,String>)` supplies your own producer (never closed by the
plugin), `getHandler()` exposes `getPublishedCount()`, `getFailedCount()`, `getDiscardedCount()` (events of rolled-back transactions).

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): a Spring Boot loan-application process whose history is streamed to Kafka
and aggregated by a small consumer into an analytics view. Run it with `docker compose up -d --wait`,
`./mvnw spring-boot:run` (from `plugins/history-kafka/example`, wrapper at the repository root), and
`./mvnw verify` for the Testcontainers IT.

## Development

```bash
./mvnw -B -pl plugins/history-kafka -amd verify            # needs Docker (Colima: export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock)
./mvnw -B -pl plugins/history-kafka/history-kafka-plugin -am verify -Doperaton.version=2.1.6
```

Design notes and best-guess decisions:

* Events are serialized and buffered per command context while the transaction runs (so a
  serialization problem fails the business transaction before commit); a `COMMITTED` transaction
  listener sends the buffer, a `ROLLED_BACK` one drops it.
* Event payload: simple-typed bean properties of the history event, discovered by reflection per
  class (dates as ISO-8601). Unknown event classes get a derived category and are published too.
* The producer is closed when the engine closes (a pre-command interceptor recognises the engine's
  package-private shutdown command by class name) or via `close()`.
* kafka-clients 4.2.x is a regular dependency; Jackson 2 is `provided` (the engine ships it, and a
  second pinned version breaks its Jackson modules). Broker image in tests: `apache/kafka:3.8.0`.
* The published message `id` is a random UUID; there is no deterministic idempotency key.
* Not done: Kafka transactions, topic creation, decision input/output lists, header propagation of
  variables. Compatibility: 1.0/1.1 not tried (javax namespace).
* Inspiration: the idea of an engine-to-Kafka history bridge from viadee/camunda-kafka-polling-client
  (BSD-3, archived); nothing was copied, so there is no NOTICE entry.

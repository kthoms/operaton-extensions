# Kafka connector

Produce to and consume from Apache Kafka in Operaton processes: send records after the engine transaction commits, start processes and correlate messages from records.

## When to use

- A process must **publish an event** (order confirmed, payment failed) to Kafka, and the event must only exist if the process state it announces was committed.
- Kafka records must **start process instances or correlate messages** to waiting instances, with business key, correlation keys and variables taken from the record key, headers and JSON value.

Not for: exactly-once end to end (delivery is at-least-once, see below; use a transactional outbox when a lost record is not acceptable), streaming the engine's history (use a history plugin), Avro or Protobuf payloads (keys and values are UTF-8 text, JSON is understood), and Camunda 8 connectors (this is a fresh implementation for Operaton).

### Delivery semantics

| Side | Guarantee |
|---|---|
| Outbound, `AFTER_COMMIT` (default) | The record is sent by a transaction listener on `COMMITTED`: nothing is sent when the transaction rolls back. The commit and the send are not atomic: if the JVM dies in between, or the broker is unreachable or does not acknowledge within the timeout, the record is lost (or, after a timeout, possibly delivered anyway). The failure is logged at ERROR and passed to the `DeliveryFailureHandler`; with `failure-mode: THROW` it is also thrown to the caller of the engine command, although the process state is already committed. |
| Outbound, `IMMEDIATE` | Sent synchronously inside the transaction; offset and partition are available as output; a rollback does not recall the record. |
| Inbound | At-least-once. The offset of a record is committed only after the engine call succeeded (or the record was dead-lettered or skipped). A crash between the engine call and the commit processes the record again: make starts idempotent (business key) and correlation tolerant. |

## Installation

Plain engine or any JVM application:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-kafka</artifactId>
  <version>0.1.0</version>
</dependency>
```

Register the plugin after the connect plugin, then close it after the engine:

```java
KafkaConnectorPlugin kafka = new KafkaConnectorPlugin();
kafka.setBootstrapServers("localhost:9092");
kafka.setSubscriptions(List.of(InboundSubscription.builder("orders")
    .topics("order-placed").startProcess("order-process").businessKey("$.orderId").build()));
configuration.setProcessEnginePlugins(List.of(new ConnectProcessEnginePlugin(), kafka));
// ... after the engine is closed: kafka.close();
```

Spring Boot:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-kafka-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

and set `operaton.extensions.connector.kafka.bootstrap-servers`. The starter brings `operaton-engine-plugin-connect`, which the Operaton starter registers, so `kafka-produce` works in BPMN without further setup.

Shared engine: put `operaton-connector-kafka` and its dependencies (`kafka-clients`, `jackson-databind`) into the shared `lib/` and add `org.operaton.extensions.connector.kafka.KafkaConnectorPlugin` as a process engine plugin in `bpm-platform.xml` with the properties of the table below. The Operaton Connect SPI keeps connectors in a JVM-wide registry: the `kafka-produce` connector uses the producer of the last plugin started in the JVM.

## Usage

### Produce: connector `kafka-produce`

```xml
<serviceTask id="publish" name="Publish order-confirmed">
  <extensionElements>
    <operaton:connector>
      <operaton:inputOutput>
        <operaton:inputParameter name="topic">order-confirmed</operaton:inputParameter>
        <operaton:inputParameter name="key">${execution.processBusinessKey}</operaton:inputParameter>
        <operaton:inputParameter name="value">${confirmation}</operaton:inputParameter>
      </operaton:inputOutput>
      <operaton:connectorId>kafka-produce</operaton:connectorId>
    </operaton:connector>
  </extensionElements>
</serviceTask>
```

Parameters (the same for the delegate):

| Parameter | Meaning |
|---|---|
| `topic` | required |
| `key`, `value` | text; a map, list or bean is written as JSON, a Spin value as its JSON/XML text |
| `headers` | map of header name to value |
| `partition` | optional target partition |
| `timeoutMs` | wait for the acknowledgement, default `send-timeout` |
| `deliveryMode` | `AFTER_COMMIT` (default) or `IMMEDIATE` |
| `processHeaders` | add the headers below, default `true` |
| `traceparent`, `tracestate` | W3C trace context; default: the process variables of the same names, when `traceparent` is valid |
| `bootstrapServers`, `producerProperties` | connector only: a producer for this call when none is configured on the engine |

Headers added by default: `operaton-process-instance-id`, `operaton-process-definition-id`, `operaton-business-key`, `operaton-activity-id`, and `traceparent`/`tracestate`. Explicit `headers` win.

Output parameters of the connector: `deliveryMode`, `topic` and, for `IMMEDIATE`, `partition`, `offset`, `timestamp`.

### Produce: delegate

`operaton:delegateExpression="${kafkaProduce}"` (a Spring bean from the starter, an expression bean registered by the plugin on plain engines) with the parameters above as input parameters of the service task. For `IMMEDIATE` the task gets the local variables `sentPartition`, `sentOffset`, `sentTimestamp`.

### Consume: subscriptions

A subscription consumes topics and per record either starts a process (`start-process`) or correlates a message (`correlate-message`). Values come from **source expressions**:

| Source | Value |
|---|---|
| `key`, `value`, `topic`, `partition`, `offset`, `timestamp` | record key, whole value (text), metadata |
| `header:<name>` | last header with the name, as text |
| `$`, `$.a.b[0]`, `$['a.b']` | a path in the JSON value: text, number or boolean for scalars, compact JSON text for objects and arrays |
| `const:<text>` | a constant |

A trailing `?` makes a source optional; a missing required value makes the record poison. Business key, correlation keys (message correlation: the target must have equal variables) and variables are mapped this way. Without `variables`, the whole value is set as `payload`.

Failure handling:

- A record that cannot be decoded as UTF-8, is not JSON where a path needs it, or lacks a required value is **poison**: it is published to the dead-letter topic with `x-dlt-reason`, `x-dlt-subscription`, `x-dlt-original-topic`, `-partition`, `-offset` headers (`on-poison: dead-letter`, default) or logged and skipped (`skip`).
- An **engine failure** (no process waits for the message, the process throws, optimistic locking) does not commit the offset: the partition is rewound to the record, paused for `retry-backoff` (polling continues, so the consumer stays in the group) and the record is retried, in order. After `max-attempts` (default 0 = never) it is poison.
- **Shutdown** wakes the consumers, finishes the record in progress, commits its offset and leaves the group.

## Configuration

Spring Boot properties `operaton.extensions.connector.kafka.*` (the plugin's setters have the same names in camel case, see `KafkaConnectorPlugin`):

| Property | Default | Meaning |
|---|---|---|
| `bootstrap-servers` | none (integration off) | `bootstrap.servers` |
| `producer.properties.<kafka property>` | | passed to the Kafka producer (defaults: `acks=all`, `max.block.ms`=send timeout; serializers are fixed) |
| `producer.send-timeout` | `30s` | wait for the acknowledgement |
| `producer.failure-mode` | `LOG` | `LOG` or `THROW` for a failed after-commit send |
| `consumer.properties.<kafka property>` | | passed to every consumer (default `auto.offset.reset=earliest`; auto commit, deserializers and `group.id` are fixed) |
| `consumer.shutdown-timeout` | `15s` | wait for the record in progress on stop |
| `subscriptions[n].name` | required | unique name (threads, default group and dead-letter topic) |
| `subscriptions[n].topics` | required | topics |
| `subscriptions[n].group-id` | `operaton-<name>` | consumer group |
| `subscriptions[n].action` | required | `start-process` or `correlate-message` |
| `subscriptions[n].process-definition-key` | | for `start-process` |
| `subscriptions[n].message-name` | | for `correlate-message` |
| `subscriptions[n].tenant-id` | | tenant of the definition or message |
| `subscriptions[n].business-key` | | source of the business key |
| `subscriptions[n].correlation-keys.<variable>` | | sources for message correlation |
| `subscriptions[n].variables.<variable>` | `payload: value` | sources of the variables to set |
| `subscriptions[n].value-format` | `JSON` | `JSON` or `TEXT` (no `$` paths) |
| `subscriptions[n].concurrency` | `1` | consumer threads |
| `subscriptions[n].on-poison` | `dead-letter` | `dead-letter` or `skip` |
| `subscriptions[n].dead-letter-topic` | `<name>.dlt` | create it, or enable topic auto-creation |
| `subscriptions[n].max-attempts` | `0` | engine attempts before poison, 0 = unlimited |
| `subscriptions[n].retry-backoff` | `1s` | pause before a retry |
| `subscriptions[n].poll-timeout` | `500ms` | |
| `subscriptions[n].properties.<kafka property>` | | consumer properties of this subscription |

Plain engine plugin properties: `bootstrapServers`, `producerProperties`, `consumerProperties`, `sendTimeoutMillis`, `deliveryFailureMode`, `deliveryFailureHandler`, `subscriptions` (list of `InboundSubscription`), `shutdownTimeoutMillis`, `autoStartConsumers`, `registerDelegateBean`.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.0 | yes |
| 2.1 | yes |
| 2.2 | yes |

The starter is built against the Spring Boot version of the parent POM.

## Example

[`example/`](example/): an order process on Spring Boot, PostgreSQL and Kafka. An `order-placed` record starts it, a payment step decides, and `order-confirmed` or `order-rejected` is published after the commit.

## Development

```bash
./mvnw -B -pl connectors/kafka -amd verify                                   # needs Docker
./mvnw -B -pl connectors/kafka/operaton-connector-kafka -am verify -Doperaton.version=2.1.6
```

On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. Tests use a real broker (`apache/kafka`) through Testcontainers and an embedded engine on H2.

Design decisions and best guesses:

- Fresh implementation; the Connect SPI registration was modelled on the way `operaton-connect-http-client` registers its provider. No code was ported.
- The connector gets its producer from the plugin through the JVM-wide connector registry (the SPI instantiates connectors without arguments), wired in `postProcessEngineBuild`, after the connect plugin loaded the connectors.
- The delegate reads its parameters from the task's input parameters (local variables) so the singleton bean holds no per-task state.
- Keys and values are bytes on the wire and UTF-8 text in mappings; no Avro, Protobuf or schema registry.
- `jackson-databind` 2.x is a compile dependency (JSON mapping); it is pinned to the 2.22 line because the engine's FEEL module refuses older databinds.
- Source expression syntax (`$.path`, `header:`, `const:`, `?`) is an own, small subset; it is not full JSONPath.
- After-commit failures default to `LOG`, because throwing after the commit would fail a job that has already completed.
- Known limitations: no outbox, no transactional (exactly-once) producer, no pattern subscriptions, no per-record parallelism inside a partition, and one Kafka configuration per JVM for the connector.

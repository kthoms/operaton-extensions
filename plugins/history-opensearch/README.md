# History OpenSearch Plugin

Indexes Operaton history as current-state documents into OpenSearch after the engine transaction
commits, so process instances, activities, tasks, variables and incidents can be searched, filtered
and aggregated (and shown in OpenSearch Dashboards) without touching the engine database.

## When to use

Use it when the question is "which orders from this customer are stuck, how long do approvals take,
what is open for the warehouse team" and the history tables are the wrong tool: you want full-text and
keyword search, aggregations, dashboards and retention by dropping old indices. Documents are
written only for **committed** transactions; a rolled-back command indexes nothing.

What you get is one **current-state document per entity**, upserted by id: the `start` event creates the
document, later `update`/`end` events merge their fields into it (start and end time, duration, state,
assignee, variable value, ...). A query for a process instance therefore returns one document with its
start time *and* end time, not a pile of events.

| Kind | Index (default) | Document id |
|---|---|---|
| process instance | `operaton-process-instances-YYYY.MM` | process instance id |
| activity instance | `operaton-activity-instances-YYYY.MM` | activity instance id |
| task | `operaton-tasks-YYYY.MM` | task id |
| variable | `operaton-variables-YYYY.MM` | variable instance id (the name is the `variableName` field) |
| incident | `operaton-incidents-YYYY.MM` | incident id |

Not for: an audit trail or event stream (use the [History Kafka Plugin](../history-kafka/), which
publishes every event, or keep the database history), driving process logic from search results, or
exactly-once indexing. The write happens *after* the database commit and cannot be part of it:

| Setup | Guarantee |
|---|---|
| `send-mode: async` (default) | at-most-once: documents queued in the JVM are written in bulk requests by a background thread; a crash before that loses them, and write errors are logged and counted (`getFailedCount()`), they cannot reach the caller. |
| `send-mode: sync` + `failure-policy: fail` | the caller gets a `HistoryOpenSearchException` when OpenSearch does not accept the documents after the retries. The business transaction **is already committed** (it cannot be rolled back); retrying it is safe because indexing is an upsert by id (no duplicates): at-least-once for callers that retry. Blocks the engine thread while writing. |
| `failure-policy: log-and-count` | failures are logged at `ERROR` and counted; nothing is thrown. |

Variable values are not indexed by default (they may contain personal data); stack traces
(`exceptionStacktrace`, `errorDetails`) never are. Jobs, external task logs, user operation logs,
decision instances and identity links are not indexed (they are logs, not current state); variable
*detail* (the history of each value) is not either, only the current value.

## Installation

**Spring Boot** (Operaton Spring Boot starter 2.x):

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-history-opensearch-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

**Plain engine**: add `operaton-plugin-history-opensearch` (the starter is optional) and register the plugin:

```java
HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
plugin.setUris(List.of("https://search.example.com:9200"));
plugin.setUsername("operaton");
plugin.setPassword(System.getenv("OPENSEARCH_PASSWORD"));
configuration.getProcessEnginePlugins().add(plugin);
```

or in `operaton.cfg.xml`:

```xml
<property name="processEnginePlugins"><list>
  <bean class="org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchPlugin">
    <property name="uris"><list><value>http://search:9200</value></list></property>
  </bean>
</list></property>
```

**Shared engine / distribution**: put the jar into the container's `lib/` and add the plugin class and
properties to the `process-engine` element of `bpm-platform.xml`. The plugin has **no third-party
dependency**: it talks to the OpenSearch REST API with `java.net.http` and uses the engine's own Jackson
(`provided`), see [Development](#development) for why.

Needs OpenSearch 2.x (verified with 2.19; the REST calls used, composable index templates,
`_bulk` with scripted upserts (Painless), `_search`, `_mget`, are also available in Elasticsearch 7.8+, which is
not part of the tested matrix). The cluster user needs `indices:admin/index_template/put`, `create_index`
and `indices:data/write/bulk*` plus `read` on the history indices (the plugin also searches them).

## Usage

Nothing changes in the models. Enable the plugin and every history event of the engine's history level
(`full` by default) that passes the filter is turned into a document change after commit. Example
process instance document, after the process ended:

```json
{"processInstanceId":"b1f0...","processDefinitionKey":"order-fulfilment","processDefinitionVersion":1,
 "startTime":"2026-11-28T10:15:30.120Z","endTime":"2026-11-28T10:16:02.870Z","durationInMillis":32750,
 "state":"COMPLETED","lastEvent":"end","updatedAt":"2026-11-28T10:16:02.871Z", ...}
```

Query it like any other index:

```bash
curl -s localhost:9200/operaton-process-instances-*/_search -H 'content-type: application/json' -d '
 {"query":{"bool":{"filter":[{"term":{"processDefinitionKey":"order-fulfilment"}},{"term":{"state":"ACTIVE"}}]}}}'
```

* **Fields** are the simple-typed properties of Operaton's history event classes (so new properties of a
  later Operaton version are indexed too), dates as ISO-8601 instants, plus `lastEvent` (the last event
  that touched the document) and `updatedAt`. Only fields an event carries are written, so a later event
  never blanks an earlier value.
* **Duration**: `durationInMillis` is the engine's; when the end event does not carry the start time (database
  history handler off), a script inside OpenSearch derives it once both times are known.
* **Variables**: `variableName`, `variableType`, `revision`, `state` (`CREATED` or `DELETED`) and, with
  `include-variable-values: true`, the typed value in `valueText` (also searchable as text, `valueText.raw` for
  exact match), `valueLong`, `valueDouble`, `valueBoolean` or `valueDate`. Text longer than
  `max-variable-value-length` is cut and flagged `valueTruncated`. Binary and serialized-object values are not indexed.
* **Index names** are `<index-prefix>-<kind>-<date>`; `index-date-pattern` is a `DateTimeFormatter` pattern in
  UTC (`yyyy.MM` monthly, `yyyy.MM.dd` daily, empty: one index per kind). A document lives in the index of the
  moment its entity began and **stays there**: when a process instance starts in January and ends in
  February, the end event updates the January document (see Development for how the plugin finds it).
  Retention is therefore "delete old indices" (ISM, Curator, or by hand); an index can be deleted once nothing
  that began in that period is still running.
* **Templates**: at engine startup the plugin installs one composable index template per kind (mappings,
  optional shards/replicas), always the same definition, so doing it again is harmless. Strings the plugin
  does not know become `keyword`. Set `manage-templates: false` if the cluster is managed elsewhere (then
  install the equivalent mappings yourself: `GET _index_template/operaton-*` on a cluster started by the plugin shows them).
* **Filter** `event-types`: categories `process-instance`, `activity-instance`, `task-instance`, `variable-instance`,
  `incident`, or `category.event` such as `process-instance.end`, `task-instance.complete`.
* With `keep-database-handler: true` (default) the history tables are written as before. With `false` the
  database handler is replaced (the history tables stay empty).
* Do not combine with an explicit `historyEventHandler` in the engine configuration: the plugin refuses to start then.
* Counters on `plugin.getHandler()`: `getIndexedCount()`, `getFailedCount()`, `getDiscardedCount()` (events of
  rolled-back transactions), `getRetryCount()`; `flush(Duration)` writes the queue (asynchronous mode).
  Documents are searchable after the next index refresh (1 s by default).

## Configuration

Spring Boot prefix `operaton.extensions.plugin.history-opensearch.`; the plugin's setters have the same names
in camelCase (durations are `...Millis` setters). Invalid values (unknown category, bad index name, both
password and API key, ...) stop startup.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Register the plugin (Spring Boot only). |
| `uris` | `http://localhost:9200` | Endpoints; requests rotate over them and skip one that fails. |
| `username`, `password` | none | Basic authentication. |
| `api-key` | none | API key (base64 `id:secret`), sent as `Authorization: ApiKey ...`; excludes basic auth. |
| `tls.trust-store`, `.trust-store-password`, `.trust-store-type` | JVM default, `PKCS12` | Trust store for the server certificate. |
| `tls.key-store`, `.key-store-password`, `.key-store-type` | none, `PKCS12` | Client certificate (mutual TLS). |
| `tls.insecure-skip-verify` | `false` | Do not verify certificate and host name. Development only; logs a warning. |
| `connect-timeout`, `request-timeout` | `5s`, `30s` | HTTP timeouts. |
| `index-prefix` | `operaton` | Start of every index name (lower case, `a-z0-9._-`). |
| `index-date-pattern` | `yyyy.MM` | Date part of the index names (UTC); empty for none. |
| `number-of-shards`, `number-of-replicas` | cluster default | Settings of new indices (in the template). |
| `manage-templates` | `true` | Install/update the index templates at startup. |
| `event-types` | empty (all) | Categories or `category.event` to index. |
| `failure-policy` | `fail` | `fail` or `log-and-count`. |
| `send-mode` | `async` | `async` (background queue) or `sync` (write before the command returns). |
| `batch-size` | `500` | Documents per bulk request. |
| `flush-interval` | `1s` | Longest wait of a queued document before its bulk request. |
| `queue-capacity` | `10000` | Queue size; a full queue makes committing threads wait `send-timeout`, then the policy applies. |
| `max-retries` | `3` | Retries of a failed request (I/O, 429, 5xx) or of rejected documents (429, version conflict). |
| `initial-backoff`, `max-backoff` | `200ms`, `5s` | Back-off: doubles per retry up to the maximum. |
| `send-timeout` | `30s` | Sync mode: wait per transaction; async: wait for a queue slot and for the queue to drain on shutdown. |
| `keep-database-handler` | `true` | `false` replaces the database history handler. |
| `include-variable-values` | `false` | Index variable values. |
| `max-variable-value-length` | `4096` | Longest text value indexed. |
| `lookup-cache-size` | `100000` | Remembered document-to-index assignments (time-based indices). |

Secrets (`password`, `api-key`, store passwords) are never logged; the startup log line and `toString()` of
the settings redact them. Error messages carry the document's category, id, index and the OpenSearch error type, never
field values (OpenSearch's own `reason` text, cut to 300 characters, may quote a value for mapping errors).

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

OpenSearch: 2.19.1 in the tests and the example.

## Example

[`example/`](example/): a Spring Boot order-fulfilment process whose history is searchable in OpenSearch (orders
by customer, open tasks, orders per state with the average duration). Run it with `docker compose up -d --wait`
(add `--profile dashboards` for OpenSearch Dashboards), `./mvnw spring-boot:run` (from
`plugins/history-opensearch/example`, wrapper at the repository root), and `./mvnw verify` for the Testcontainers IT.

## Development

```bash
./mvnw -B -pl plugins/history-opensearch -amd verify            # needs Docker (Colima: export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock)
./mvnw -B -pl plugins/history-opensearch/operaton-plugin-history-opensearch -am verify -Doperaton.version=2.1.6
```

Tests: engine tests (H2) against a real OpenSearch 2.19 in Testcontainers (documents after commit, nothing on
rollback, merge of start and end events, variables, incidents, filter, template, index placement across months with the
engine clock moved), a fault-injecting stub for what a healthy cluster cannot do on demand (503/429, rejected items,
auth headers, TLS with a keytool-made certificate), and unit tests of configuration and templates.

Design notes and best-guess decisions:

* **HTTP client: `java.net.http` plus the engine's Jackson, no OpenSearch client library.** `opensearch-java` with
  the Apache HttpClient 5 transport would add a second HTTP stack, `org.opensearch.client:opensearch-java` and its own
  Jackson/JSON-P/Parsson dependencies to the engine's class path (in a Spring Boot or shared-engine setup, next to the
  engine's Jackson modules and the FEEL Scala module that need the engine's Jackson). The plugin needs four REST calls
  (`PUT _index_template`, `_bulk`, `_search`, `_mget`), which are a few hundred lines on the JDK client. Jackson is
  `provided`; the plugin has no runtime dependency. Cost: no sniffing/node discovery, HTTP/1.1 and no compression.
* Events are mapped and buffered per command context while the transaction runs (a mapping problem fails the business
  transaction before commit); a `COMMITTED` transaction listener hands the buffer over, a `ROLLED_BACK` one drops it.
* **Upsert**: every document operation is a bulk `update` with `scripted_upsert`; a small Painless script merges the
  event's non-null fields and derives `durationInMillis`. Consecutive events of one document in a batch are merged
  client-side into one operation. Events of one document that reach OpenSearch out of order (two engine nodes, sync
  mode) merge field by field, so start/end data is never lost, but a field that both events carry (`state`,
  `assignee`) takes the value of the event that arrives last.
* **Index placement** (time-based indices): history events are deltas and an `end` event usually lacks the start time.
  A document is placed by the start/creation time the event carries (scope events carry it when the database handler is
  kept: it merges the start time into the event before this handler runs), by a cache of recent assignments, by a
  `_search` for the id over the kind's indices, and by a real-time `_mget` over the current and the 10-seconds-ago
  index (which covers a document another node wrote and that is not searchable yet). Only an entity unknown to
  OpenSearch (it began before the plugin was installed, or its creation event was lost) is placed by the current time.
  Changing `index-prefix` or `index-date-pattern` for a running system does not move existing documents; events of
  still-running entities then create new documents in the new indices.
* Variable *detail* events (`update-detail`, one per value change) are skipped; a variable's document holds its
  current value. The document id is the variable instance id; one process instance with the variable `amount` has
  one document with `variableName: amount`.
* One writer thread per engine preserves commit order; it is a daemon thread and is stopped (draining the queue within
  `send-timeout`) when the engine closes, recognised by its package-private shutdown command by class name, or by `close()`.
* Startup failures: with `failure-policy: fail` the engine does not start when OpenSearch is unreachable or refuses
  the templates; with `log-and-count` the engine starts and the templates are installed before the first write that succeeds.
* Not done: Elasticsearch/OpenSearch Serverless (SigV4) authentication, bearer tokens, index lifecycle policies and
  aliases (retention is the operator's), decision instances, jobs and user operation logs, nested documents, process
  instance migration events (a `migrate` event merges like an update), a dead-letter queue for permanently rejected
  documents (they are logged with context and counted). Compatibility with Operaton 1.x was not tried.

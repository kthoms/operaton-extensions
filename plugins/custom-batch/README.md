# Custom Batch Plugin

Framework for custom batch operations on the Operaton engine: typed batch job handlers, a builder, progress tracking, failure handling and cleanup.

## When to use

Use it when a piece of work has to be done for many items (thousands of invoices, accounts, documents) and must not run as one big transaction, one thread or one request. You define a batch type with a handler, create a batch for N items, and the engine splits the items into batch execution jobs of `invocationsPerBatchJob` items. The jobs run on the job executor of every node, in their own transactions. The batch is a regular engine batch: Cockpit's batch view, the REST API and `ManagementService.createBatchStatisticsQuery()` show its progress, its failed jobs and incidents, and it survives restarts because it lives in the database.

- Choose it over a process that loops with a multi-instance task when there is no business process around the work, or when the item count is large.
- Choose it over the engine's built-in batches (migration, deletion, set retries) when the operation is your own.
- Do not use it for work that must run exactly at a time (use a timer), for streams of events (use an external task client or a message queue), or for item counts so large that the items themselves should not live in the database: the items are stored as JSON in the engine's byte array table, so pass ids and let the handler load the data.

Two handler styles:

| Style | Base class | A job's items run | When a job fails |
|---|---|---|---|
| Chunk | `CustomBatchJobHandler<T>` | together, in the job's transaction | everything rolls back, the job is retried with all its items |
| Item | `ItemCustomBatchJobHandler<T>` | one by one, each in its own transaction | only the failing items roll back; the retry runs only them |

## Installation

Plain engine or any other embedding:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-custom-batch</artifactId>
  <version>VERSION</version>
</dependency>
```

Register the plugin with the handlers on every node that creates or runs custom batches:

```java
configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(new RecalculateTotalsHandler(dataSource)));
```

The engine itself must provide `jackson-databind` (the items are stored as JSON). Operaton brings it through its FEEL engine; with an exotic setup that excludes it, add it yourself.

Spring Boot: the starter registers every handler bean through a `CustomBatchPlugin` bean and exposes a `CustomBatches` bean.

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-custom-batch-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

Shared engine or distribution: put the jar into the shared `lib/` folder next to the jar with your handlers, and register a subclass of the plugin that creates the handlers (`bpm-platform.xml` or `processes.xml` cannot inject a list of objects):

```java
public class InvoiceBatchPlugin extends CustomBatchPlugin {
    public InvoiceBatchPlugin() {
        super(new RecalculateTotalsHandler());
    }
}
```

```xml
<plugin><class>com.acme.InvoiceBatchPlugin</class></plugin>
```

## Usage

### 1. Write a handler

```java
public class RecalculateTotalsHandler extends CustomBatchJobHandler<Long> {

    public RecalculateTotalsHandler() {
        super("recalculate-totals", Long.class);   // batch type, item type
    }

    @Override
    protected void executeItems(List<Long> invoiceIds, CommandContext commandContext) {
        // at most invocationsPerBatchJob ids; commits with the job
    }
}
```

The type is the batch type and the job handler type: unique per engine, at most 255 characters, and not one of the engine's own (`instance-migration`, ...). The plugin refuses to start the engine otherwise.

For per-item transactions extend `ItemCustomBatchJobHandler<T>` and implement `executeItem(T item, CommandContext context)`; override `onItemFailure` to report a failed item (the default logs a warning).

Items must be a type Jackson can write and read: a record, a POJO, a `String`, a number. For a generic item type pass a `ParameterizedType` to the constructor. To use `java.time` types or other modules override `configureMapper(JsonMapper.Builder)`. Override `describeItem(T)` if items are sensitive: the description appears in the message of failed jobs, which is stored in the database and shown in Cockpit.

Handlers are called concurrently by the job executor; keep them stateless or thread-safe.

### 2. Register and create a batch

```java
Batch batch = CustomBatchBuilder.of(handler, invoiceIds)
        .invocationsPerBatchJob(50)   // 1000 ids become 20 jobs
        .exclusive(false)             // let the jobs of this batch run in parallel
        .create(processEngine);
```

Inside a delegate or listener call `create()` without arguments: the batch is created in the running transaction, so it appears (or rolls back) together with the rest of the command.

### 3. Follow the progress

```java
CustomBatches batches = new CustomBatches(processEngine);
BatchProgress progress = batches.progress(batch.getId());
progress.completedJobs(); progress.remainingJobs(); progress.failedJobs(); progress.fractionCompleted();
```

The numbers are the engine's batch statistics. When the monitor job has seen all jobs done the engine removes the batch; `progress` then answers from the history (`finished() == true`), which needs a history level above `none`. An unknown id raises `NotFoundException`.

### 4. Failures, retries and cleanup

A failing job is retried by the engine (`defaultNumberOfRetries`, or a retry time cycle) and raises an incident when the retries are used up. A batch with a failed job stays open: the monitor does not finish it.

```java
List<FailedBatchJob<Long>> failed = batches.failedJobs(handler, batchId); // job id, retries, message, items still to do
batches.retryFailedJobs(batchId, 3);   // new tries for the jobs without retries left
batches.suspend(batchId);              // or activate(batchId)
batches.delete(batchId, true);         // jobs and stored configurations go; true also deletes the history
```

For an item handler a failed job holds exactly the failed items (a copy of the job configuration with only them is committed before the job fails, so the retry never repeats items that succeeded). For a chunk handler it holds all items of the chunk.

### Restarts and several nodes

All state is in the database: the batch's remaining items, one stored configuration per created job, the jobs. Stop the engine at any point and start it again (with the plugin registered): the seed job continues where it stopped and no item is lost or processed twice. A node without the handler registered cannot run the batch: its seed and batch jobs fail with an error (engine NullPointerException on the seed job) and are retried until a node with the handler picks them up, so register the plugin on every node of a cluster.

## Configuration

Engine settings that shape a batch (all standard engine properties):

| Property | Default | Meaning |
|---|---|---|
| `invocationsPerBatchJob` | 1 | Items per job when the builder does not say |
| `invocationsPerBatchJobByBatchType` | none | Items per job by batch type, overrides the above |
| `batchJobsPerSeed` | 100 | Jobs created per seed job run |
| `batchPollTime` | 30 | Seconds between two checks of the monitor job whether the batch is done |
| `batchJobPriority` | 0 | Priority of the jobs, effective with `jobExecutorAcquireByPriority` |
| `batchOperationHistoryTimeToLive` | none | How long the history of finished batches is kept |

Builder (`CustomBatchBuilder`):

| Method | Default | Meaning |
|---|---|---|
| `items(Collection)` | required | The items, in order; copied; no `null` |
| `invocationsPerBatchJob(int)` | engine setting | Items per job, at least 1 |
| `jobsPerSeed(int)` | engine setting | Jobs per seed job run, at least 1 |
| `jobPriority(long)` | engine setting | Priority of seed, monitor and batch jobs |
| `exclusive(boolean)` | `true` | `false` lets the jobs of the batch run in parallel |
| `tenantId(String)` | none | Tenant of the batch and its jobs |

Plugin (`CustomBatchPlugin`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `handlers` | list of handlers | none | The handlers to register (constructor or `addHandler`) |
| `historyTimeToLive` | map type to ISO 8601 duration | none | Adds entries to the engine's `batchOperationsForHistoryCleanup` |

Spring Boot starter:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `operaton.extensions.plugin.custom-batch.enabled` | boolean | `true` | Register the plugin and the `CustomBatches` bean |
| `operaton.extensions.plugin.custom-batch.history-time-to-live.<type>` | ISO 8601 duration | none | History time to live of finished batches of that type |

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | 2.0.x (latest patch) |
| 2.1 | 2.1.x (latest patch) |
| 2.2 | 2.2.x (latest patch) |

## Example

[`example/`](example/): a Spring Boot application recalculates the totals of 1000 invoices in PostgreSQL as a custom batch, started from a process, with its progress read from the batch statistics; `./mvnw verify` runs it end to end with Testcontainers.

## Development

```bash
./mvnw -B -pl plugins/custom-batch -amd verify                                              # whole extension incl. example (Docker needed)
./mvnw -B -pl plugins/custom-batch/operaton-plugin-custom-batch -am verify -Doperaton.version=2.0.0   # one Operaton version
```

Tests: engine tests on embedded H2 (`ProcessEngineExtension`) step through the seed, batch and monitor jobs by hand and also let the real job executor run a batch with parallel jobs; they cover several jobs per batch, the split defaults, tenant/priority/exclusive, failing chunks and failing items (including incidents, failed job reporting and the retry after a fix), deletion and suspension, and restart safety (an engine is closed in the middle of a batch and rebuilt on the same database; an engine without the handler fails loudly and leaves the batch intact). The starter has context runner tests, the example has a PostgreSQL integration test that also runs an item handler through Spring transactions.

### Design notes and decisions

- Operaton 2.0, 2.1 and 2.2 expose the same public `BatchJobHandler`, `BatchEntity`, `BatchJobConfiguration` and `BatchJobDeclaration` API; the plugin implements `BatchJobHandler` directly (not `AbstractBatchJobHandler`, whose `execute` is final and bound to id lists) and sets the batch's execution start time like the engine's handlers do. The one difference that mattered: `Context.getCommandContext()` returns `null` without a command in 2.0 and 2.1 and throws in 2.2; the builder handles both.
- Items are JSON (Jackson), not Java serialization as in the original: no deserialisation of untrusted bytes, readable in the database, versioned (`"version":1`). The engine's own Gson is shaded inside the engine jar, so the plugin depends on `jackson-databind` (`provided`) like the other extensions.
- The seed job re-writes the remaining items each time it runs (as the engine's own batches do): for hundreds of thousands of items keep items small (ids) and raise `batchJobsPerSeed`.
- `ItemCustomBatchJobHandler` uses `getCommandExecutorTxRequiresNew()` of the engine: a new Spring transaction with Spring, a new connection otherwise. A job therefore needs two connections: size the pool for twice the job executor's maximum pool size. In a standalone engine only writes through the engine's persistence (or your own transaction handling) roll back with a failing item; raw JDBC writes on the engine's connection are committed with the connection.
- The permission check on creation is `checkCreateBatch(Permissions.CREATE)`: with authorization enabled the user needs CREATE on BATCH.
- The builder does not write a user operation log entry (the engine's own batches do).
- Best guesses: names (`executeItems`, `executeItem`), the 100 character item description, the five failures spelled out in a failed job's message, `exclusive` defaulting to `true` as in the original.

### Sources

Ported from [camunda-community-hub/camunda-platform-7-custom-batch](https://github.com/camunda-community-hub/camunda-platform-7-custom-batch) (Apache-2.0, archived; the project moved to holunda-io/c7): `CustomBatchBuilder`, `CustomBatchJobHandler`, `CustomBatchCreateJobsHandler` (now `AbstractCustomBatchJobHandler`), `CustomBatchConfiguration`, `CustomBatchHandlerPlugin` (now `CustomBatchPlugin`), the JSON configuration helpers (now `ConfigurationCodec`) and the idea of `FailsafeCustomBatchJobHandler` (now `ItemCustomBatchJobHandler`). Adapted from `org.camunda` to `org.operaton` and reworked as described above; the original has no per-file copyright headers. See [NOTICE](NOTICE) and the root NOTICE.

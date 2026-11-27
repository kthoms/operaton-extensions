# Rust External Task Client

Async external task client for [Operaton](https://operaton.org) in Rust: it fetches and locks external tasks over the REST API with long polling, runs one async handler per task on tokio and reports the result (complete, BPMN error or failure with retries).

## When to use

Use it for workers written in Rust that process Operaton external tasks (`operaton:type="external"` service tasks and events with an external implementation) over `/engine-rest/external-task`. It needs only HTTP access to the engine, so the worker can run anywhere and scale independently. Not for Java workers (use the Operaton Spring Boot external task client).

## Installation

```bash
cargo add operaton-external-task-client
cargo add tokio --features rt-multi-thread,macros,signal
```

The crate is `operaton-external-task-client` (library `operaton_external_task_client`), edition 2021, on tokio, reqwest with rustls (no OpenSSL), serde_json, thiserror and tracing.

## Usage

```rust
use operaton_external_task_client::{
    Client, ClientConfig, Completion, ExternalTask, SubscriptionOptions, TaskError, TaskService,
    Variable,
};

#[tokio::main]
async fn main() -> operaton_external_task_client::Result<()> {
    let client = Client::new(
        ClientConfig::builder("http://localhost:8080/engine-rest")
            .basic_auth("demo", "demo")
            .build()?,
    )?;

    let handle = client.subscribe(
        "fraud-check",
        |task: ExternalTask, service: TaskService| async move {
            let amount = task.variable("amount").and_then(Variable::as_f64).unwrap_or(0.0);
            if amount > 10_000.0 {
                // handleBpmnError: an error boundary event with code FRAUD_SUSPECTED catches it
                return Err(TaskError::bpmn("FRAUD_SUSPECTED", "amount too high"));
            }
            service.extend_lock(std::time::Duration::from_secs(60)).await.ok(); // optional
            Ok(Completion::new().variable("riskScore", 10).variable("approved", true)) // complete
        },
        SubscriptionOptions::new()
            .variables(["amount"])
            .process_definition_key("fraud-check"),
    )?;

    client.run_until(tokio::signal::ctrl_c()).await?; // start, wait, graceful stop
    handle.unsubscribe();
    Ok(())
}
```

**Handler contract.** A handler is an async closure or function `(ExternalTask, TaskService) -> Result<Completion, TaskError>`.

- `Ok(completion)` completes the task with its variables (unless the handler already settled the task through the service).
- `Err(TaskError::Bpmn { .. })` (`TaskError::bpmn(code, message)`) becomes `handleBpmnError`.
- Any other `TaskError::Failure` (`TaskError::failure(msg)`, `TaskError::from_error(&err)`, optional `.with_details`, `.with_retries`, `.with_retry_timeout`) becomes `handleFailure`. Without explicit retries the client counts down: the task's retries minus one, or `default_retries - 1` on the first failure; at 0 the engine creates an incident. A panic in a handler is caught and reported the same way.
- A locked task is never dropped silently: an undecodable task is failed with 0 retries, a task whose topic was unsubscribed meanwhile is unlocked, and a failed report to the engine is logged (the lock then expires).

**Task service** (`TaskService`, one per task, cloneable): `complete`, `complete_with_local`, `handle_failure(message, details, retries, retry_timeout)`, `handle_bpmn_error(code, message, variables)`, `extend_lock`, `unlock`, `set_variables`, `set_local_variables`. `Client::task_service(&task)` gives one outside of a handler.

**Variables.** `Variable` keeps the engine's type: `String`, `Boolean`, `Integer` (i32), `Long` (i64), `Double`, `Date` (the engine's timestamp text, no date-time dependency), `Json` (a `serde_json::Value`, sent as the engine's JSON text), `Object`, `File`, `Null`, and `Other` for types without a variant (`Short`, `Bytes`, `Xml`, ...), passed through unchanged. Conversions never change the type (`From<i32>` is Integer, `From<i64>` is Long); a value that does not fit its type is a decode error. `Variables` is a `BTreeMap<String, Variable>`.

**Subscription options** (`SubscriptionOptions`): `lock_duration`, `variables` (names; default all), `local_variables`, `business_key`, `process_definition_id(s)`, `process_definition_key(s)`, `process_definition_version_tag`, `tenant_id(s)`, `without_tenant_id`, `process_variable(name, value)` (filter), `include_extension_properties`. `subscribe` returns a `SubscriptionHandle`; `unsubscribe()` stops fetching that topic. Each topic can be subscribed once; subscribe before or after `start()`.

**Lifecycle.** `start().await` spawns the fetch loop; `stop().await` stops fetching and waits for running handlers (at most `shutdown_timeout`, then `Error::ShutdownTimeout`); `run_until(signal)` does both. A long poll in flight at `stop` is cancelled, so tasks the engine locked for it become available again once their lock expires.

Library code never uses `unwrap`/`expect` (denied by clippy); logging goes through `tracing`.

## Configuration

`ClientConfig::builder(base_url)`:

| Option | Default | Meaning |
|---|---|---|
| `base_url` | (required) e.g. `http://localhost:8080/engine-rest` | REST root of the engine |
| `worker_id` | generated (process id, time, counter) | lock owner; unique per worker process |
| `max_tasks` | `10` | tasks per fetch and handlers running at once (1 to 10000); a fetch never asks for more than free slots |
| `async_response_timeout` | `30 s` | long polling timeout (zero: no long polling, `backoff.initial` pause between empty polls) |
| `lock_duration` | `30 s` | default lock duration (per subscription: `lock_duration`) |
| `use_priority` | `true` | fetch by task priority |
| `basic_auth(user, password)`, `bearer_auth(token)`, `auth(Auth)` | none | authentication (secrets are redacted in `Debug`) |
| `backoff` | `Backoff { initial: 500 ms, max: 60 s, factor: 2.0 }` | delay after consecutive failed fetches; reset by a successful one |
| `default_retries` | `3` | retries assumed for a task that has none yet |
| `retry_timeout` | `10 s` | delay before a failed task can be fetched again |
| `request_timeout` | `30 s` | timeout of requests (a long poll gets this plus `async_response_timeout`) |
| `shutdown_timeout` | `60 s` | how long `stop` waits for running handlers |

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | `operaton/operaton` latest 2.0.x |
| 2.1 | `operaton/operaton` latest 2.1.x |
| 2.2 | `operaton/operaton` latest 2.2.x |

## Example

[`example/`](example/): a fraud-check worker (topic `fraud-check`) as a small binary crate with its BPMN model (approve, or block with a BPMN error), `compose.yaml` starting `operaton/operaton:2.2.2`, and a helper that deploys the model and starts two transactions.

## Development

```bash
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test                                   # unit tests, mocked engine (wiremock)
OPERATON_URL=http://localhost:8080/engine-rest cargo test --features integration
cargo package
```

CI runs the same stages through `.github/scripts/client-build.sh clients/rust <stage>` with a native `rustup` stable toolchain (`rustfmt`, `clippy`). The integration tests (`OPERATON_URL`, `OPERATON_VERSION`) deploy a BPMN with an external task, run the client end to end and assert completion with typed variables, failures counting retries down to an incident, BPMN error routing to the boundary event and filters. Each test uses its own topic, so they share one engine. Start an engine with `docker run -d --rm -p 8080:8080 operaton/operaton:2.2.2`. The example has its own tests: `cd example && OPERATON_URL=... cargo test --features integration`.

This crate was developed and verified without a local Rust install, using the official `rust:1` Docker image as the toolchain (engine in a container on a user-defined network, `OPERATON_URL=http://<engine-container>:8080/engine-rest`); verified against 2.0.0, 2.1.6 and 2.2.2.

Design decisions and best guesses:

- Fresh implementation. The only Rust client found, `danielkelemen/camunda-external-task-client-rust` (Apache-2.0, last updated 2022, no activity), was not used; no code was ported, so there is no NOTICE.
- One polling task fetches for all topics in a single request, bounded by free handler slots (a semaphore); shutdown waits by re-acquiring all slots.
- A handler's `Ok` completes the task; the service can settle it explicitly instead.
- `Date` is kept as the engine's text, to avoid a date-time dependency; parse it in the handler.
- Fetched with the engine's default `deserializeValues=false`; File content is not part of fetch-and-lock (received `File` variables have `content: None`).
- `set_variables` uses `POST /process-instance/{id}/variables`, `set_local_variables` uses `POST /execution/{id}/localVariables`.
- Not implemented: task priority sorting options, downloading file variable content, custom TLS roots and proxies beyond reqwest's environment defaults.

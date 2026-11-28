# Rust External Task Client — example

A fraud-check worker: a process model sends every incoming transaction to the external task topic `fraud-check`; the Rust worker scores it, approves normal transactions and blocks suspicious ones with a BPMN error that the model routes to a "Transaction blocked" end event.

## What it shows

- subscribing with a variable list and a process definition filter;
- reading typed variables (`amount` as Double, `country` as String);
- a handler that returns variables (complete), a `TaskError::bpmn` (BPMN error) and a `TaskError::failure` for malformed input (retries, then an incident);
- graceful shutdown on Ctrl-C.

## Process model

`fraud-check.bpmn`: start event "Transaction received" -> external task "Check transaction" (topic `fraud-check`) -> end event "Transaction approved"; an error boundary event (`FRAUD_SUSPECTED`) on the task leads to the end event "Transaction blocked". Open it in Cockpit (Deployments) or bpmn.io to see the diagram.

## Prerequisites

Rust stable (edition 2021), Docker.

## Run it

```bash
docker compose up -d --wait             # Operaton 2.2.2 on http://localhost:8080 (demo / demo)
cargo run --bin deploy_and_start        # deploys the BPMN, starts TX-1001 (small) and TX-1002 (risky)
cargo run --bin worker                  # processes both, Ctrl-C to stop
```

Cockpit: http://localhost:8080/operaton/app/cockpit (demo / demo). Set `OPERATON_URL` for another engine, `OPERATON_USER` / `OPERATON_PASSWORD` for credentials (an empty `OPERATON_USER` means none) and `RUST_LOG=debug` for more logs.

## Walkthrough

- TX-1001 (120.50 from DE): score 0; the process ends in "Transaction approved" with `riskScore=0`, `decision=approved`.
- TX-1002 (25,000 from KP): score 90 (large amount and high-risk country), at or above the threshold of 80; the worker throws `FRAUD_SUSPECTED`, the process ends in "Transaction blocked" with `riskScore=90`.
- A transaction without `amount` fails technically: the task is retried (default 3 attempts) and then shows an incident in Cockpit.

## How it works

`src/lib.rs`: `assess` is the pure scoring and decision, `fraud_check` the handler. `src/bin/worker.rs` configures the client, subscribes and runs until Ctrl-C. `src/bin/deploy_and_start.rs` talks to the REST API.

## Tests

`cargo test` runs the scoring unit tests; `OPERATON_URL=http://localhost:8080/engine-rest cargo test --features integration` deploys the model and runs the worker end to end (approved and blocked).

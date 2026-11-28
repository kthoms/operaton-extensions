# Clients

Clients are external task clients for languages other than Java. They talk to the engine's REST
API (fetch-and-lock). Layout and manifest: [repository-design.md](repository-design.md#one-client-external-task-client);
naming and packages: [module-structure.md](module-structure.md#clients). Documentation shape,
example and compatibility rules are the same as for Java extensions
([documentation.md](documentation.md), [examples.md](examples.md), [compatibility.md](compatibility.md)),
with the differences below.

## Toolchains

| | JavaScript/TypeScript (`js`) | Python (`python`) | Go (`go`) | Rust (`rust`) |
|---|---|---|---|---|
| Version | Node 22+ | Python 3.10+ | Go 1.23+ | stable, edition 2021 |
| Project | `package.json`; ESM and CJS build | `pyproject.toml`, hatchling, `src/` layout | `go.mod` | `Cargo.toml` |
| Format | prettier | `ruff format` | `gofmt` | `rustfmt` |
| Lint | eslint | `ruff check`; mypy optional | `go vet` | `clippy -D warnings` |
| Tests | vitest | pytest | standard `testing` | `cargo test` |
| Style | strict TypeScript | type hints on the public API | `context.Context` first parameter on blocking calls | `tokio` async, `reqwest` |
| Registry | npm | PyPI | tag only | crates.io |

Format and lint checks must pass in CI; fix formatting with the formatter, not by hand.
Every source file carries the license header (as a comment, in the language's style) as in
[coding.md](coding.md#license-header). The fail-loudly rule applies: raise or return errors with
context, never swallow them, no placeholder results; stubs raise "not implemented: …".

## Common feature set

Every client implements the feature list in
[repository-design.md](repository-design.md#one-client-external-task-client): fetch-and-lock with
long polling, complete, handle failure, handle BPMN error, extend lock, unlock, typed variables
(including JSON), topic subscriptions with variable filters, business key and process definition
filters, basic and bearer authentication, configurable max tasks, back-off on errors, graceful
shutdown. A feature missing in one language is a bug or an entry in its README's `## Usage`
limitations, not a silent gap.

## Common API shape

Names follow each language's conventions (`snake_case`, `PascalCase`, …); the shape is the same.

| Concept | Shape |
|---|---|
| Client and configuration | `Client(config)`: base URL, authentication, worker id, max tasks, lock duration, long-polling timeout, back-off |
| Subscription | `subscribe(topic, handler, options)`; options: lock duration, variables to fetch, filters |
| Handler | receives the task and a task service |
| Task service | `complete(variables)`, `handleFailure(message, details, retries, retryTimeout)`, `handleBpmnError(code, message, variables)`, `extendLock(duration)`, `unlock()` |
| Variables | typed values (string, number, boolean, JSON, ...); no implicit conversion that loses the type |
| Lifecycle | `start()` and `stop()`; stop finishes tasks in flight |

A handler that throws or returns an error leads to `handleFailure` with context; the client never
drops a locked task silently.

## Tests

- Unit tests need no engine.
- Integration tests run against a **real engine**: they read `OPERATON_URL` (base URL of the
  engine) and `OPERATON_VERSION` (the image tag, `operaton/operaton:<version>`). Locally and in
  CI the engine is started from that image: with Testcontainers where the language has it,
  otherwise by a container the test or script starts, or `compose.yaml`. CI runs one job per minor
  in `compatibility`.
- A toolchain that is not installed locally (Go, Rust, ...) runs from its official Docker image
  (`golang:1.x`, `rust:1`), with the worktree mounted and a named cache volume; CI uses the native
  `setup-*` actions.
- Integration tests deploy a process, run the worker end to end and assert the process result.
  No sleeps; poll with a timeout.

## Example

`example/` is a near-real-world worker with a `compose.yaml` starting `operaton/operaton:<version>`
([examples.md](examples.md) applies, except for the Spring Boot and Maven parts). It deploys its
BPMN, so `docker compose up -d` and one command start a working scenario.

## Publishing

Releases are done by the release workflow, never by hand
([versioning-and-release.md](versioning-and-release.md)).

| Language | Target | Mechanism |
|---|---|---|
| `js` | npm `@operaton-extensions/external-task-client` | npm with provenance |
| `python` | PyPI `operaton-external-task-client` | trusted publishing |
| `go` | `github.com/kthoms/operaton-extensions/clients/go` | the tag `clients/go/v<version>` |
| `rust` | crates.io `operaton-external-task-client` | `cargo publish` |

## Ports and attribution

Clients may be informed by existing clients. Ports follow
[licensing-and-attribution.md](licensing-and-attribution.md). Known sources:

| Project | License | Note |
|---|---|---|
| camunda-external-task-client-js | Apache-2.0 | keep headers, add "Ported from" line, NOTICE entries |
| camunda-external-task-client-python3 | Apache-2.0 | same |
| citilinkru/camunda-client-go | MIT | the MIT copyright and permission notice must be kept in the ported files and in the NOTICE entry |

Camunda-branded names, namespaces and endpoints are changed to Operaton's when porting.

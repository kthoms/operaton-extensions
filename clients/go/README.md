# Go External Task Client

An external task client for Operaton in Go: it long-polls the engine's REST API for the tasks of
your topics, runs your handlers concurrently and reports complete, failure (with retries) or BPMN
error. Go 1.23+, standard library only (`net/http`, `encoding/json`, `log/slog`).

## When to use

Use it for workers written in Go that process Operaton external tasks (`operaton:type="external"`
service tasks) over the REST API, without embedding anything in the engine.

Not for: starting processes, querying history or other engine APIs. The client covers the
`/external-task` endpoints (fetch-and-lock, complete, failure, BPMN error, extend lock, unlock)
and setting variables on the task's process instance. Use plain `net/http` for the rest.

## Installation

```bash
go get github.com/kthoms/operaton-extensions/clients/go
```

```go
import operaton "github.com/kthoms/operaton-extensions/clients/go"
```

Releases are tags of the form `clients/go/v<version>`.

## Usage

```go
client, err := operaton.New(operaton.Config{
    BaseURL: "http://localhost:8080/engine-rest",
    // Username: "demo", Password: "demo",   // basic authentication
    // BearerToken: "...",                   // or bearer authentication
})
if err != nil { log.Fatal(err) }

unsubscribe, err := client.Subscribe("credit-check",
    func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
        amountVar, err := task.RequireVariable("amount")
        if err != nil { return err }
        amount, err := amountVar.AsInt64()
        if err != nil { return err }
        if amount < 0 {
            return &operaton.BPMNError{Code: "INVALID_AMOUNT", Message: "negative amount"}
        }
        return svc.Complete(ctx, operaton.Variables{"approved": operaton.Boolean(amount < 10_000)})
    },
    operaton.WithVariables("amount"),
    operaton.WithLockDuration(time.Minute),
)
_ = unsubscribe // call it to stop fetching this topic

ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
defer stop()
err = client.Run(ctx) // blocks; on cancel stops fetching and waits for running handlers
```

`Start()` and `Stop(ctx)` do the same without blocking. Subscriptions may be added before or after
`Start`.

### Handler results

| Handler returns | The client sends |
|---|---|
| `nil` after `svc.Complete(...)` (or another terminal call) | nothing more |
| `nil` without any terminal call | `complete` without variables |
| a `*BPMNError` (also wrapped, found with `errors.As`) | `bpmnError` with its code, message, variables |
| a `*FailureError` (`operaton.Fail(err, retries, timeout)`, `operaton.NoRetry(err)`) | `failure` with exactly those retries and retry timeout |
| any other error, or a panic (with stack trace in the details) | `failure`, retries counted down (see `Config.Retries`) |

A task is never dropped silently: if reporting fails (for example the lock was lost) the error is
logged and the lock expires, so the engine offers the task again. Only one of `Complete`,
`HandleFailure`, `HandleBPMNError` and `Unlock` succeeds per task; a second call returns
`ErrAlreadyHandled`.

### Task service

| Method | Endpoint |
|---|---|
| `Complete(ctx, vars)`, `CompleteWithLocalVariables(ctx, vars, local)` | `POST /external-task/{id}/complete` |
| `HandleFailure(ctx, Failure{Message, Details, Retries, RetryTimeout})` | `.../failure` |
| `HandleBPMNError(ctx, code, message, vars)` | `.../bpmnError` |
| `ExtendLock(ctx, d)` | `.../extendLock` |
| `Unlock(ctx)` | `.../unlock` |
| `SetVariables(ctx, vars)`, `SetLocalVariables(ctx, vars)` | `POST /process-instance/{id}/variables`, `/execution/{id}/localVariables` |

### Typed variables

Variables keep their engine type; the client never converts between types. Build values with
`String`, `Boolean`, `Integer` (int32), `Long` (int64), `Double`, `Date`, `JSON`, `XML`, `Bytes`,
`File`, `Object` and `Null`; read them with `AsString`, `AsBool`, `AsInt64`, `AsFloat64`, `AsTime`,
`AsJSON(&v)`, `AsBytes`. Reading with the wrong accessor returns an error wrapping
`ErrTypeMismatch`. Values are kept as raw JSON, so a Long above 2^53 survives a round trip.
`Double(NaN)` and invalid JSON fail when the variable is sent, not silently.
Serialized `Object` values are fetched as strings (`deserializeValues=false`).

### Subscription options

`WithLockDuration`, `WithVariables`, `WithLocalVariables`, `WithBusinessKey`,
`WithProcessDefinitionID(s)`, `WithProcessDefinitionKey(s)`, `WithProcessDefinitionVersionTag`,
`WithTenantID(s)`, `WithoutTenantID`, `WithProcessVariablesFilter`, `WithExtensionProperties`,
`WithConcurrency`.

### Behaviour worth knowing

- **Long polling**: every subscription has its own fetch loop. The engine only wakes a long poll
  for new tasks, so a task whose retry timeout or lock expires is picked up at the next poll cycle,
  up to `AsyncResponseTimeout` later. Lower `AsyncResponseTimeout` when short retry delays matter.
- **Concurrency**: a subscription runs at most `MaxConcurrency` handlers and asks the engine only
  for as many tasks as it has free slots, so it never locks tasks it cannot start.
- **Graceful stop**: `Stop` cancels the pollers, waits for running handlers and lets them report.
  If its context ends first, the handler contexts are cancelled and `Stop` returns the error. A
  fetch cancelled while in flight may leave tasks locked until their lock duration passes.
- **Errors**: REST errors are `*APIError` (status, engine exception type and message);
  `IsNotFound(err)` tests for 404. Failed fetches back off exponentially with jitter.
- **Logging**: structured `log/slog` records (topic, task id, process instance id); inject your own
  `*slog.Logger` through `Config.Logger`.

## Configuration

| Option (`Config` field) | Default | Meaning |
|---|---|---|
| `BaseURL` | `http://localhost:8080/engine-rest` | REST root of the engine, including `/engine-rest` |
| `WorkerID` | `operaton-go-<host>-<random>` | Worker id used to lock tasks |
| `MaxTasks` | `10` | Tasks requested per fetch-and-lock |
| `MaxConcurrency` | `MaxTasks` | Handlers running at once per subscription |
| `AsyncResponseTimeout` | `30s` | Long-polling timeout; negative disables long polling |
| `PollInterval` | `1s` | Pause after an empty fetch when long polling is disabled |
| `LockDuration` | `30s` | Lock time of fetched tasks (per subscription: `WithLockDuration`) |
| `UsePriority` | `false` | Engine returns tasks by priority |
| `Retries` | `3` | Total attempts per task; the first failure reports `Retries-1`, later ones count down, 0 raises an incident |
| `RetryTimeout` | `0` | Delay before a failed task is available again |
| `Backoff` | `500ms`, max `60s`, factor `2` | Pause after failed fetches (`Initial`, `Max`, `Factor`), with jitter |
| `RequestTimeout` | `30s` | Deadline of every call except the long poll (which adds `AsyncResponseTimeout`) |
| `ShutdownTimeout` | `30s` | How long `Run` waits for handlers after its context ends |
| `Username`, `Password` | unset | HTTP basic authentication |
| `BearerToken` / `BearerTokenFunc` | unset | Bearer authentication, static or from a function called per request |
| `HTTPClient` | `&http.Client{}` | Custom client (proxy, TLS, transport); copied, its transport wrapped for authentication |
| `Logger` | `slog.Default()` | Structured logger |

Basic and bearer authentication are mutually exclusive; `New` rejects invalid configuration.
Use a custom `http.RoundTripper` in `HTTPClient` for other schemes (the client only adds the
`Authorization` header when basic or bearer is configured).

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.2 | `operaton/operaton` latest 2.2.x (2.2.2) |
| 2.1 | `operaton/operaton` latest 2.1.x (2.1.6) |
| 2.0 | `operaton/operaton` 2.0.0 |

## Example

[`example/`](example/): a credit-check worker for the topic `credit-check` with the BPMN model,
`compose.yaml`, and `deploy` and `start` commands. It shows completing with typed variables, a BPMN
error for unknown customers and a retried transient failure.

## Development

Run everything inside `clients/go`. CI uses `actions/setup-go` natively and the stages of
`.github/scripts/client-build.sh clients/go <install|lint|unit|integration|package>`:

```bash
go vet ./... && test -z "$(gofmt -l .)"      # lint
go test -race ./...                          # unit tests (httptest fake engine, no Docker)
docker run -d --rm -p 8080:8080 operaton/operaton:2.2.2
OPERATON_URL=http://localhost:8080/engine-rest go test -tags=integration ./...   # needs a running engine
```

The integration tests deploy a BPMN with a unique process key and topic per test, so they may share
an engine. They cover typed variables, failure with retries until an incident, BPMN error caught by
a boundary event, extend lock and set variables, unlock, the business key filter, concurrency and
graceful stop. To test against another version, start `operaton/operaton:<version>`.

Without a local Go installation (how this module was developed) use the official image:

```bash
docker network create opx-go
docker run -d --rm --name operaton-go --network opx-go operaton/operaton:2.2.2
docker run --rm --network opx-go -e GOTOOLCHAIN=local -e OPERATON_URL=http://operaton-go:8080/engine-rest \
  -v "$PWD/../..":/src -w /src/clients/go golang:1.25 go test -tags=integration -race ./...
```

### Design decisions and best guesses

- Package name `operaton`, module `github.com/kthoms/operaton-extensions/clients/go` (required by
  the release tag `clients/go/v<version>`). `go.mod` says `go 1.23`; developed with Go 1.25.
- One fetch loop per subscription (own lock duration, filters, concurrency) instead of one loop for
  all topics, so subscriptions can be added and removed independently.
- A handler that returns `nil` without reporting completes the task without variables; a handler
  that wants a different outcome calls the task service or returns an error.
- `Config.Retries` means total attempts (first failure reports `Retries-1`); the engine's
  `retries` field is `null` until the first failure.
- Handler failures use the engine's retry counter, so the counter survives worker restarts.
- No engine-side deserialization of `Object` variables; they stay strings.
- No process start, message correlation or other engine APIs (out of scope for a task client).

### Sources and attribution

Ideas and parts of the code derive from
[citilinkru/camunda-client-go](https://github.com/citilinkru/camunda-client-go) (MIT, Copyright
(c) 2019 Citilink LLC): the processor's polling loop with worker pool and panic recovery, the
external task request shapes and the typed variable model, reworked here (context-aware API,
`log/slog`, free-slot fetching, raw-value variables). Ported files (`subscription.go`,
`variable.go`, `task.go`) carry a "Ported from" line; the MIT copyright and permission notice is in
[`NOTICE`](NOTICE) and [`LICENSE-THIRD-PARTY`](LICENSE-THIRD-PARTY).

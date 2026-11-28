# TypeScript External Task Client

An external task client for [Operaton](https://operaton.org) in TypeScript: it polls the engine's
REST API with fetch-and-lock (long polling), runs your handler per task and reports the result.
ESM and CommonJS builds with types, Node.js 22+, no runtime dependencies (global `fetch`).

## When to use

Use it for workers written in Node.js or TypeScript that process Operaton external tasks
(`operaton:type="external"`) over REST: integrations, scripts and services outside the JVM. It is a
port of [camunda-external-task-client-js](https://github.com/camunda/camunda-external-task-client-js)
(Apache-2.0), modernised to TypeScript and `fetch`, pointed at Operaton's `/engine-rest`.

## Installation

```bash
npm install @kthoms/operaton-external-task-client
```

## Usage

```ts
import { BpmnError, Client, Variables } from "@kthoms/operaton-external-task-client";

const client = new Client({ baseUrl: "http://localhost:8080/engine-rest", workerId: "shipper" });

const subscription = client.subscribe<{ orderId: string; items: { sku: string }[] }>(
  "ship-order",
  async ({ task, taskService }) => {
    const orderId = task.variables.get("orderId"); // string | undefined, typed
    if (!orderId) throw new Error("orderId is missing"); // reported as a failure, with retries
    await taskService.complete(Variables.from({ trackingNumber: "TRK-1", shippedAt: new Date() }));
  },
  { variables: ["orderId", "items"], processDefinitionKey: "order-shipping" },
);

client.start();
// later: subscription.unsubscribe(); await client.stop(); // waits for running handlers
```

**Handlers** receive `{ task, taskService }`. `task` has the engine's fields (`id`, `topicName`,
`retries`, `processInstanceId`, `businessKey`, ...) and read-only `task.variables`. `taskService` is
bound to the task:

| Call                                                                      | Effect                                                              |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `complete(variables?, localVariables?)`                                   | completes the task; variables are a `Variables` or a plain object   |
| `handleFailure({ errorMessage, errorDetails?, retries?, retryTimeout? })` | reports a failure; at `retries: 0` the engine opens an incident     |
| `handleBpmnError(code, message?, variables?)`                             | raises a BPMN error for an error boundary or event sub process      |
| `extendLock(ms)`, `unlock()`                                              | prolongs or releases the lock                                       |
| `setVariables(variables, { local? })`                                     | sets process (or local execution) variables without ending the task |

A handler must end the task with `complete`, `handleFailure`, `handleBpmnError` or `unlock`.
If it **throws**, the client calls `handleFailure` (message, stack as details, retries counted
down from `maxAttempts`); throwing a `BpmnError(code, message, variables)` calls `handleBpmnError`
instead. If it **returns without ending the task**, the task is unlocked and a warning is logged.
A locked task is never dropped silently. `client.taskService` offers the same calls outside of a
handler, addressed by task or task id.

**Variables.** `Variables<T>` types `get` and `set` from your variable map. `set` infers the engine
type: `string` String, `boolean` Boolean, 32-bit integer Integer, larger safe integer or `bigint`
Long, other numbers Double, `Date` Date, `null` Null, `Uint8Array` Bytes, `OperatonFile` File and
any other object or array Json. Use `typed.long(...)`, `typed.integer(...)`, `typed.object(value,
"com.acme.Order")`, `typed.nil()` and friends to choose the type yourself, and `getTyped(name)` to
read the type along with the value. `undefined` and non-finite numbers are rejected loudly.
File variables are loaded on demand (`await file.load()`); create them with
`OperatonFile.fromPath()` or `fromBuffer()`.

**Events** (on the client, `client.on(...)`): `subscribe`, `unsubscribe`, `poll:start`,
`poll:success`, `poll:error`, `poll:stop`, `handler:success`, `handler:error`, and
`<action>:success` / `<action>:error` for `complete`, `handleFailure`, `handleBpmnError`,
`extendLock`, `unlock`, `setVariables`.

**Logging** is pluggable: pass any object with `debug/info/warn/error` (for example a pino or
winston adapter) as `logger`; `consoleLogger(level)` is the default, `silentLogger` logs nothing.

**Shutdown.** `await client.stop()` cancels the long poll in progress and waits for running
handlers (`stop({ timeout })` gives up waiting after a while). Tasks the engine locked at the very
instant of the cancel are released when their lock expires; tasks that arrive after `stop()` are
unlocked immediately.

Limitations: `Long` values read from the engine arrive as JSON numbers, so integers beyond
2^53 lose precision (a `bigint` is returned only when the engine sends the value as a string);
values of type `Object` in a non-JSON data format (for example Java serialization) are handed over
as the raw string. Camunda 7's `lock` call and keycloak interceptor are not ported (use
`auth.bearer` with a token function instead).

## Configuration

Options of `new Client({...})`:

| Option                  | Default                          | Meaning                                                                                                           |
| ----------------------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `baseUrl`               | required                         | REST base URL, for example `http://localhost:8080/engine-rest`                                                    |
| `workerId`              | random per client                | worker id used for locks                                                                                          |
| `maxTasks`              | `10`                             | tasks per fetch-and-lock request                                                                                  |
| `maxParallelExecutions` | unlimited                        | cap on handlers running at the same time                                                                          |
| `asyncResponseTimeout`  | `30000`                          | long polling: ms the engine holds a request open; `0` disables                                                    |
| `lockDuration`          | `50000`                          | default lock in ms (per topic: subscription option `lockDuration`)                                                |
| `interval`              | `300`                            | pause in ms between polls (none right after a poll that returned tasks)                                           |
| `backoff`               | `{ factor: 2, maxDelay: 60000 }` | after failed polls the pause is `interval * factor^failures`, capped                                              |
| `usePriority`           | `true`                           | fetch higher priority tasks first                                                                                 |
| `sorting`               | none                             | `[{ sortBy: "createTime", sortOrder: "asc" \| "desc" }]`                                                          |
| `auth`                  | none                             | `{ type: "basic", username, password }` or `{ type: "bearer", token: string \| () => string \| Promise<string> }` |
| `interceptors`          | none                             | `(request) => request` functions (sync or async) run on every request after `auth`                                |
| `maxAttempts`           | `3`                              | total attempts of a task whose handler throws; the retries start at `maxAttempts - 1`                             |
| `retryTimeout`          | `5000`                           | ms before a failed task is fetched again                                                                          |
| `requestTimeout`        | `30000`                          | timeout of a request in ms (long polling time comes on top for fetch-and-lock)                                    |
| `logger`                | console, `info`                  | see above                                                                                                         |
| `autoStart`             | `false`                          | start polling in the constructor                                                                                  |
| `fetch`                 | global `fetch`                   | custom implementation (proxy, TLS, tests)                                                                         |

Subscription options (`client.subscribe(topic, handler, options)`): `lockDuration`, `variables`
(names to fetch), `localVariables`, `businessKey`, `processDefinitionId(s)`,
`processDefinitionKey` / `processDefinitionKeys`, `processDefinitionVersionTag`,
`processVariables`, `tenantId` / `tenantIds`, `withoutTenantId`, `includeExtensionProperties`.
`subscribe` returns `{ topic, unsubscribe() }`; a topic can have one handler.

## Compatibility

Verified by the integration tests (`npm run test:integration`) against `operaton/operaton` images
(from `extension.yml`):

| Operaton | Verified with                                    |
| -------- | ------------------------------------------------ |
| 2.0      | `operaton/operaton:2.0.0` (12 integration tests) |
| 2.1      | `operaton/operaton:2.1.6` (12 integration tests) |
| 2.2      | `operaton/operaton:2.2.2` (12 integration tests) |

Node.js 22 or newer. Operaton 1.x is not claimed.

## Example

[`example/`](example/): an order-shipping worker (topic `ship-order`) with its BPMN, a
`compose.yaml` starting Operaton and a script that deploys the process and starts orders.

## Development

```bash
cd clients/js
npm ci
npm run lint               # eslint, prettier --check, tsc --noEmit
npm test                   # unit tests (vitest, mocked fetch), no engine needed
npm run build              # tsup: ESM + CJS + .d.ts in dist/
# integration tests: a real engine, deploys BPMN over REST, runs the client end to end
docker run -d --rm --name operaton-it -p 8080:8080 operaton/operaton:2.2.2
OPERATON_URL=http://localhost:8080/engine-rest npm run test:integration
docker stop operaton-it
```

`OPERATON_URL` defaults to `http://localhost:8080/engine-rest`; use another host port when 8080
is taken. CI runs the same stages through `.github/scripts/client-build.sh clients/js <stage>`.

Source and license: ported from
[camunda-external-task-client-js](https://github.com/camunda/camunda-external-task-client-js)
(Apache-2.0, Copyright Camunda Services GmbH): files with a "Ported from" line, listed in
[NOTICE](NOTICE) and the root NOTICE. Rewritten in TypeScript on `fetch` (instead of `got`); new:
typed `Variables<T>`, bound task service, `BpmnError`, back-off, graceful `stop()` with timeout,
interceptors as async functions, unsubscribe handle.

Decisions and guesses: handlers must end the task and a handler that returns without doing so
unlocks the task (the original silently left it locked); `maxAttempts` (default 3) drives the
retries because the engine reports `retries: null` on the first failure; long polling is on by
default (30 s); polling does not start until `start()` (the original's `autoPoll` default is
opt-in here as `autoStart`); the worker id is random instead of the constant `some-random-id`;
`stop()` aborts the in-flight long poll instead of waiting for it; Long values are `number`
unless the engine sends a string. Tests are in `tests/unit` and `tests/integration`; integration
tests use unique process keys and topics, so they can share an engine. The npm package is released
by the release workflow with provenance, never by hand.

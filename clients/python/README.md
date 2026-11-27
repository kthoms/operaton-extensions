# Python External Task Client

External task client for [Operaton](https://operaton.org) in Python: threaded and asyncio workers that fetch and lock external tasks over the REST API, run your handler and report the result.

## When to use

Use it for workers written in Python that process Operaton external tasks (`operaton:type="external"` service tasks, send/intermediate/end events with an external implementation) over `/engine-rest/external-task`. It needs nothing but HTTP access to the engine, so the worker can run anywhere and scale independently.

Choose the sync `ExternalTaskClient` (thread pool; blocking handlers, the easy default) or `AsyncExternalTaskClient` (asyncio; coroutine handlers, many slow I/O-bound tasks). Not for: long-running work without lock extension (use `service.extend_lock`), or Java workers (use the Operaton Spring Boot external task client).

## Installation

```bash
pip install operaton-external-task-client
```

Python 3.10 or newer; the only dependency is `httpx`.

## Usage

```python
from operaton_external_task_client import (
    BpmnError, ClientConfig, ExternalTaskClient, TaskFailure, Variable,
)

def process_invoice(task, service):
    amount = task.variables["amount"]                 # typed values decoded to Python
    if amount <= 0:
        raise BpmnError("INVOICE_REJECTED", "no amount", {"reason": "empty"})  # handle_bpmn_error
    if not partner_up():
        raise TaskFailure("partner down", retries=3, retry_timeout=30)         # handle_failure
    return {"approved": True, "total": Variable.long(amount * 100)}            # complete

client = ExternalTaskClient(ClientConfig(base_url="http://localhost:8080/engine-rest"))
client.subscribe("process-invoice", process_invoice, variables=["amount"],
                 process_definition_key="invoice-processing")
client.run_forever()      # Ctrl-C / SIGTERM: stop fetching, finish running handlers
```

Handler contract: `handler(task, service)`.

- Returning `None` or a mapping completes the task with those variables (unless the handler
  already finished it through the service).
- Raising `BpmnError(code, message, variables)` throws a BPMN error; raising
  `TaskFailure(message, details, retries, retry_timeout)` fails with explicit retry settings; any
  other exception fails the task with `Type: message` and the traceback as error details. The
  retries default to the task's retries minus one, or `default_retries` on the first failure; at
  `0` the engine creates an incident. A locked task is never dropped silently.
- The task service offers `complete`, `handle_failure`, `handle_bpmn_error`, `extend_lock`,
  `unlock`, `set_variables(local=...)`, `set_retries` and `download_file`. `complete`, `handle_failure`,
  `handle_bpmn_error` and `unlock` finish the task; a second one raises `TaskServiceError`.

`subscribe(topic, handler, *, lock_duration, variables, local_variables, business_key,
process_definition_id(s), process_definition_key(s), process_definition_version_tag, tenant_id(s),
without_tenant_id, process_variables, include_extension_properties)` returns a `Subscription`
(`.unsubscribe()`); `@client.topic("name", ...)` is the decorator form. `variables=None` fetches
all variables, `[]` none. Subscribe before `start()`; more topics can be added while running.

Lifecycle: `start()` / `stop(timeout=None) -> bool` (or `with client:`, or `run_forever()`).
`stop()` stops fetching, unlocks tasks that arrive during shutdown and waits for running handlers.
With long polling the sync client's polling thread may be inside a request, so `stop()` can take up
to `async_response_timeout`; lower it if you need faster shutdown. The asyncio client cancels the
poll immediately.

### Asyncio

```python
import asyncio
from operaton_external_task_client import AsyncExternalTaskClient

async def handler(task, service):
    await service.extend_lock(120)
    return {"done": True}

async def main():
    client = AsyncExternalTaskClient()
    client.subscribe("process-invoice", handler)   # async def, or a plain function (runs in a thread)
    await client.run_forever()                     # SIGINT/SIGTERM stop it

asyncio.run(main())
```

### Variables

Plain Python values map to their natural type: `str` String, `bool` Boolean, `int` Integer (Long
outside 32 bit), `float` Double, `datetime` Date (naive taken as UTC), `bytes` Bytes,
`dict`/`list` Json, `None` Null. Anything else raises `VariableError` rather than guessing. Pick
the type explicitly with `Variable.string / boolean / integer / long / double / date / json /
object(serialized, type_name, data_format) / file(content, filename, mimetype, encoding) /
binary / null`. On the task, `task.variables` has the Python values, `task.typed_variables` the
`Variable` objects (type and value info), Json is parsed, Date is a `datetime`. Object values are
delivered in serialised form (`deserializeValues=false`); File content is read with
`service.download_file(name)`.

Logging goes to the stdlib logger `operaton_external_task_client`.

## Configuration

`ClientConfig` (all durations in seconds or a `timedelta`):

| Option | Default | Meaning |
|---|---|---|
| `base_url` | `http://localhost:8080/engine-rest` | REST root of the engine |
| `worker_id` | generated (host, pid, random) | lock owner; unique per worker process |
| `max_tasks` | `10` | tasks fetched and locked per request |
| `handler_concurrency` | `max_tasks` | handlers running at once; never fetches more than free slots |
| `async_response_timeout` | `30` | long polling timeout (`0`: no long polling, wait `idle_interval` between empty polls) |
| `lock_duration` | `60` | default lock duration (per subscription: `lock_duration=`) |
| `use_priority` | `True` | fetch by task priority |
| `include_extension_properties` | `True` | fetch `operaton:properties` |
| `default_retries` | `3` | retries set on a first failure |
| `default_retry_timeout` | `10` | delay before a failed task is fetched again |
| `back_off` | `BackOff(1, 2, 60, 0.1)` | initial delay, factor, maximum, jitter after failed polls; reset by a successful poll |
| `idle_interval` | `1` | wait between empty polls when long polling is off |
| `auth` | none | `BasicAuth(user, password)`, `BearerAuth(token or callable)` or any `httpx.Auth` |
| `headers` | `{}` | extra request headers |
| `request_timeout` | `30` | timeout of ordinary requests (a long poll gets this plus `async_response_timeout`) |
| `verify` | `True` | TLS verification: bool, CA bundle path or `ssl.SSLContext` |
| `service_retries` | `2` | retries of task service calls on connection errors and 502/503/504 |

Both clients accept an `http_client=` (an `httpx.Client` / `AsyncClient`) for proxies, mTLS or
custom transports; it is not closed by the client.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | `operaton/operaton` latest 2.0.x |
| 2.1 | `operaton/operaton` latest 2.1.x |
| 2.2 | `operaton/operaton` latest 2.2.x |

## Example

[`example/`](example/): an invoice-processing worker (topic `process-invoice`) with its BPMN
model (approve, or reject with a BPMN error), `compose.yaml` starting `operaton/operaton:2.2.2`,
and a script that deploys the model and starts sample invoices.

## Development

```bash
python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy
.venv/bin/pytest -m "not integration"
OPERATON_URL=http://localhost:8080/engine-rest .venv/bin/pytest -m integration
.venv/bin/python -m build
```

CI runs the same stages through `.github/scripts/client-build.sh clients/python <stage>`.
Integration tests (`OPERATON_URL`, `OPERATON_VERSION`) deploy a BPMN with an external task, run
the worker and assert completion, failure with retries and an incident, BPMN error routing,
lock extension, unlock, filters, the asyncio client, graceful stop and the example. Start an
engine with `.github/scripts/operaton-engine.sh start <version>` or
`docker run -d --rm -p 8080:8080 operaton/operaton:2.2.2`; the tests deploy uniquely named
processes and can share an engine.

Design decisions and best guesses:

- `httpx` (sync and async from one dependency) instead of the original's `requests`; durations
  are seconds, not milliseconds.
- One polling thread (or task) fetches for all topics in a single request, bounded by free handler
  slots; there is no per-topic polling.
- A handler's normal return completes the task (the original required `task.complete()`); an
  explicit service call still works.
- Fetched with `deserializeValues=false`, the client decodes Json/Date/Bytes itself so engine-side
  Java classes are never needed.
- Retries: first failure sets `default_retries`, later failures decrement the engine's value
  (as in the original client).
- The sync `stop()` waits for the in-flight long poll to return; tasks locked by it during
  shutdown are unlocked.
- Not implemented: task priority sorting options, `Short`/`Xml` helper factories (those types are
  passed through as received), Windows signal handling for the asyncio `run_forever`.

Sources: ported from
[camunda-external-task-client-python3](https://github.com/camunda-community-hub/camunda-external-task-client-python3)
(Apache-2.0); see [NOTICE](NOTICE) and the root NOTICE.

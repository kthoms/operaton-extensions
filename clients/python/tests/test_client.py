# Copyright 2026 the Operaton Extensions contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import threading
import time
from itertools import pairwise

import httpx
import pytest

from operaton_external_task_client import (
    BackOff,
    BpmnError,
    ClientConfig,
    ConfigurationError,
    ExternalTaskClient,
    OperatonError,
    TaskFailure,
    Variable,
)

from .conftest import make_task, wait_until


def new_client(engine, config) -> ExternalTaskClient:
    return ExternalTaskClient(
        config, http_client=httpx.Client(transport=httpx.MockTransport(engine))
    )


def test_start_requires_a_subscription(engine, config):
    with pytest.raises(ConfigurationError, match="no subscription"):
        new_client(engine, config).start()


def test_duplicate_topic_and_unsubscribe(engine, config):
    client = new_client(engine, config)
    sub = client.subscribe("a", lambda t, s: None)
    with pytest.raises(ConfigurationError, match="already subscribed"):
        client.subscribe("a", lambda t, s: None)
    sub.unsubscribe()
    assert client.subscriptions == []
    client.subscribe("a", lambda t, s: None)


def test_completes_with_returned_variables(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)
    seen = []

    def handler(task, service):
        seen.append(task.variables["amount"])
        return {"approved": amount_ok(task.variables["amount"])}

    def amount_ok(n):
        return n < 100

    client.subscribe("demo", handler, variables=["amount"], business_key="bk")
    with client:
        wait_until(lambda: engine.calls("/complete"))
    (body,) = engine.calls("/complete")
    assert body["variables"] == {"approved": {"value": True, "type": "Boolean"}}
    assert seen == [12]
    fetch = engine.calls("/fetchAndLock")[0]
    assert fetch["topics"][0]["variables"] == ["amount"]
    assert fetch["topics"][0]["businessKey"] == "bk"


def test_handler_may_use_the_service_itself(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)

    def handler(task, service):
        service.extend_lock(60)
        service.complete({"x": Variable.long(1)})

    client.subscribe("demo", handler)
    with client:
        wait_until(lambda: engine.calls("/complete"))
    assert len(engine.calls("/complete")) == 1
    assert engine.calls("/extendLock")


def test_exception_becomes_failure_with_context(engine, config):
    engine.push(make_task("t1"), make_task("t2", retries=2))

    def handler(task, service):
        raise ValueError("bad invoice")

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    with client:
        wait_until(lambda: len(engine.calls("/failure")) == 2)
    by_retries = sorted(b["retries"] for b in engine.calls("/failure"))
    assert by_retries == [1, 3]
    body = engine.calls("/failure")[0]
    assert body["errorMessage"] == "ValueError: bad invoice"
    assert "Traceback" in body["errorDetails"]
    assert not engine.calls("/complete")


def test_task_failure_and_bpmn_error_exceptions(engine, config):
    engine.push(make_task("t1"), make_task("t2"))

    def handler(task, service):
        if task.id == "t1":
            raise TaskFailure("retry me", "details", retries=9, retry_timeout=3)
        raise BpmnError("NO_STOCK", "empty", {"left": 0})

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    with client:
        wait_until(lambda: engine.calls("/failure") and engine.calls("/bpmnError"))
    failure = engine.calls("/failure")[0]
    assert (failure["retries"], failure["retryTimeout"], failure["errorDetails"]) == (
        9,
        3000,
        "details",
    )
    error = engine.calls("/bpmnError")[0]
    assert error["errorCode"] == "NO_STOCK"
    assert error["variables"] == {"left": {"value": 0, "type": "Integer"}}


def test_bad_return_value_is_a_failure(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: 42)
    with client:
        wait_until(lambda: engine.calls("/failure"))
    assert "must return None or a mapping" in engine.calls("/failure")[0]["errorMessage"]


def test_unserialisable_result_is_a_failure(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: {"x": object()})
    with client:
        wait_until(lambda: engine.calls("/failure"))
    assert "VariableError" in engine.calls("/failure")[0]["errorMessage"]


def test_failure_report_error_does_not_kill_the_worker(engine, config):
    engine.fail_paths["/failure"] = 500
    engine.push(make_task("t1"), make_task("t2"))
    done = []

    def handler(task, service):
        if task.id == "t1":
            raise RuntimeError("x")
        done.append(task.id)

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    with client:
        wait_until(lambda: engine.calls("/complete"))
    assert done == ["t2"]


def test_routes_by_topic(engine, config):
    engine.push(make_task("a1", "alpha"), make_task("b1", "beta"))
    seen = {}
    client = new_client(engine, config)
    client.subscribe("alpha", lambda t, s: seen.setdefault("alpha", t.id) and None)
    client.subscribe("beta", lambda t, s: seen.setdefault("beta", t.id) and None)
    with client:
        wait_until(lambda: len(engine.calls("/complete")) == 2)
    assert seen == {"alpha": "a1", "beta": "b1"}
    assert len(engine.calls("/fetchAndLock")[0]["topics"]) == 2


def test_decorator_subscription(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)

    @client.topic("demo", lock_duration=5)
    def handler(task, service):
        return {}

    with client:
        wait_until(lambda: engine.calls("/complete"))
    assert engine.calls("/fetchAndLock")[0]["topics"][0]["lockDuration"] == 5000
    assert handler is not None


def test_never_fetches_more_than_free_slots(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        max_tasks=5,
        handler_concurrency=2,
        async_response_timeout=0.05,
        service_retries=0,
    )
    for i in range(6):
        engine.push(make_task(f"t{i}"))
    gate = threading.Event()
    running, peak, lock = 0, 0, threading.Lock()

    def handler(task, service):
        nonlocal running, peak
        with lock:
            running += 1
            peak = max(peak, running)
        gate.wait(5)
        with lock:
            running -= 1

    client = new_client(engine, cfg)
    client.subscribe("demo", handler)
    with client:
        wait_until(lambda: running == 2)
        time.sleep(0.2)
        assert running == 2
        assert max(b["maxTasks"] for b in engine.calls("/fetchAndLock")) <= 2
        gate.set()
        wait_until(lambda: len(engine.calls("/complete")) == 6)
    assert peak == 2


def test_back_off_on_fetch_errors_and_recovery(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        async_response_timeout=0.05,
        back_off=BackOff(initial=0.05, factor=2, maximum=0.2, jitter=0),
    )
    stamps: list[float] = []
    failing = {"n": 3}

    def error():
        stamps.append(time.monotonic())
        if failing["n"] > 0:
            failing["n"] -= 1
            return httpx.ConnectError("down")
        return httpx.Response(200, json=[])

    engine.fetch_error = error
    client = new_client(engine, cfg)
    client.subscribe("demo", lambda t, s: None)
    with client:
        wait_until(lambda: len(stamps) >= 4)
    gaps = [b - a for a, b in pairwise(stamps)]
    assert gaps[0] >= 0.045  # initial 50 ms
    assert gaps[1] >= 0.095  # doubled
    assert gaps[2] >= 0.145  # doubled again, capped at 200 ms


def test_http_error_on_fetch_backs_off_and_keeps_running(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        async_response_timeout=0.05,
        back_off=BackOff(initial=0.02, maximum=0.05, jitter=0),
    )
    attempts = []

    def error():
        attempts.append(1)
        return httpx.Response(401, json={"type": "AuthException", "message": "nope"})

    engine.fetch_error = error
    client = new_client(engine, cfg)
    client.subscribe("demo", lambda t, s: None)
    with client:
        wait_until(lambda: len(attempts) >= 3)
        assert client.is_running


def test_stop_waits_for_running_handlers_and_unlocks_late_tasks(engine, config):
    engine.push(make_task("t1"))
    started, release = threading.Event(), threading.Event()
    finished = []

    def handler(task, service):
        started.set()
        release.wait(5)
        finished.append(task.id)

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    client.start()
    assert started.wait(5)
    stopper = threading.Thread(target=client.stop)
    stopper.start()
    time.sleep(0.2)
    assert stopper.is_alive(), "stop() must wait for the running handler"
    release.set()
    stopper.join(5)
    assert finished == ["t1"]
    assert engine.calls("/complete")


def test_stop_timeout_reports_unfinished(engine, config):
    engine.push(make_task("t1"))
    started, release = threading.Event(), threading.Event()

    def handler(task, service):
        started.set()
        release.wait(5)

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    client.start()
    assert started.wait(5)
    assert client.stop(timeout=0.1) is False
    release.set()


def test_task_for_unknown_topic_is_unlocked(engine, config):
    engine.push(make_task("t1", "gone"))
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: None)
    # the engine hands out a topic that is not subscribed (e.g. unsubscribed meanwhile)
    original = engine.__call__

    def rigged(request):
        if request.url.path.endswith("fetchAndLock") and engine.tasks:
            body = engine.tasks[:]
            engine.tasks.clear()
            return httpx.Response(200, json=body)
        return original(request)

    client._api._http = httpx.Client(transport=httpx.MockTransport(rigged))
    with client:
        wait_until(lambda: engine.calls("/unlock"))
    assert not engine.calls("/complete")


def test_started_once_and_stop_is_idempotent(engine, config):
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: None)
    client.start()
    with pytest.raises(OperatonError):
        client.start()
    assert client.stop() is True
    assert client.stop() is True


def test_run_forever_stops_from_another_thread(engine, config):
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: None)
    threading.Timer(0.3, client._stop.set).start()
    client.run_forever(install_signal_handlers=False)
    assert not client.is_running


def test_no_long_poll_waits_between_empty_polls(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        async_response_timeout=0,
        idle_interval=0.1,
    )
    client = new_client(engine, cfg)
    client.subscribe("demo", lambda t, s: None)
    with client:
        time.sleep(0.45)
    assert 2 <= len(engine.calls("/fetchAndLock")) <= 7

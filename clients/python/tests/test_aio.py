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

import asyncio
import time

import httpx
import pytest

from operaton_external_task_client import (
    AsyncExternalTaskClient,
    BackOff,
    BpmnError,
    ClientConfig,
    ConfigurationError,
    TaskFailure,
)

from .conftest import make_task


def new_client(engine, config) -> AsyncExternalTaskClient:
    async def transport(request: httpx.Request) -> httpx.Response:
        await asyncio.sleep(0.005)  # a real transport always yields to the event loop
        return engine(request)

    return AsyncExternalTaskClient(
        config, http_client=httpx.AsyncClient(transport=httpx.MockTransport(transport))
    )


async def until(predicate, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition not met within timeout")


async def test_start_requires_a_subscription(engine, config):
    with pytest.raises(ConfigurationError):
        await new_client(engine, config).start()


async def test_async_handler_completes(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)

    async def handler(task, service):
        await asyncio.sleep(0)
        return {"sum": task.variables["amount"] + 1}

    client.subscribe("demo", handler)
    async with client:
        await until(lambda: engine.calls("/complete"))
    assert engine.calls("/complete")[0]["variables"] == {"sum": {"value": 13, "type": "Integer"}}


async def test_sync_handler_runs_off_the_loop_and_service_is_awaitable(engine, config):
    engine.push(make_task("t1"))
    client = new_client(engine, config)
    seen = []

    def handler(task, service):
        time.sleep(0.05)
        seen.append(task.id)
        return {"ok": True}

    client.subscribe("demo", handler)
    async with client:
        await until(lambda: engine.calls("/complete"))
    assert seen == ["t1"]


async def test_service_calls_and_exceptions(engine, config):
    engine.push(make_task("t1"), make_task("t2"), make_task("t3", retries=1))

    async def handler(task, service):
        if task.id == "t1":
            await service.extend_lock(30)
            await service.complete({"a": 1})
        elif task.id == "t2":
            raise BpmnError("E1", "x")
        else:
            raise RuntimeError("boom")

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    async with client:
        await until(lambda: engine.calls("/failure") and engine.calls("/bpmnError"))
    assert engine.calls("/extendLock")[0]["newDuration"] == 30000
    assert engine.calls("/bpmnError")[0]["errorCode"] == "E1"
    failure = engine.calls("/failure")[0]
    assert failure["retries"] == 0
    assert failure["errorMessage"] == "RuntimeError: boom"


async def test_task_failure_explicit(engine, config):
    engine.push(make_task("t1"))

    async def handler(task, service):
        raise TaskFailure("later", retries=5, retry_timeout=1)

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    async with client:
        await until(lambda: engine.calls("/failure"))
    assert engine.calls("/failure")[0]["retries"] == 5


async def test_concurrency_is_capped(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        max_tasks=5,
        handler_concurrency=2,
        async_response_timeout=0.05,
        service_retries=0,
    )
    for i in range(5):
        engine.push(make_task(f"t{i}"))
    gate = asyncio.Event()
    running = peak = 0

    async def handler(task, service):
        nonlocal running, peak
        running += 1
        peak = max(peak, running)
        await gate.wait()
        running -= 1

    client = new_client(engine, cfg)
    client.subscribe("demo", handler)
    async with client:
        await until(lambda: running == 2)
        await asyncio.sleep(0.15)
        assert running == 2
        gate.set()
        await until(lambda: len(engine.calls("/complete")) == 5)
    assert peak == 2


async def test_back_off_and_recovery(engine):
    cfg = ClientConfig(
        base_url="http://engine.test/engine-rest",
        worker_id="w",
        async_response_timeout=0.05,
        back_off=BackOff(initial=0.05, factor=2, maximum=0.2, jitter=0),
    )
    stamps: list[float] = []

    def error():
        stamps.append(time.monotonic())
        if len(stamps) <= 2:
            return httpx.ConnectError("down")
        return httpx.Response(200, json=[])

    engine.fetch_error = error
    client = new_client(engine, cfg)
    client.subscribe("demo", lambda t, s: None)
    async with client:
        await until(lambda: len(stamps) >= 4)
    assert stamps[1] - stamps[0] >= 0.045
    assert stamps[2] - stamps[1] >= 0.095


async def test_stop_waits_for_handlers(engine, config):
    engine.push(make_task("t1"))
    started, release = asyncio.Event(), asyncio.Event()

    async def handler(task, service):
        started.set()
        await release.wait()

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    await client.start()
    await asyncio.wait_for(started.wait(), 5)
    stopper = asyncio.create_task(client.stop())
    await asyncio.sleep(0.2)
    assert not stopper.done()
    release.set()
    assert await asyncio.wait_for(stopper, 5) is True
    assert engine.calls("/complete")


async def test_stop_timeout_cancels_handlers(engine, config):
    engine.push(make_task("t1"))
    started = asyncio.Event()

    async def handler(task, service):
        started.set()
        await asyncio.sleep(30)

    client = new_client(engine, config)
    client.subscribe("demo", handler)
    await client.start()
    await asyncio.wait_for(started.wait(), 5)
    assert await client.stop(timeout=0.1) is False
    assert not engine.calls("/complete")


async def test_run_forever_ends_on_stop_signal(engine, config):
    client = new_client(engine, config)
    client.subscribe("demo", lambda t, s: None)
    runner = asyncio.create_task(client.run_forever(install_signal_handlers=False))
    await until(lambda: client.is_running)
    client._stopping.set()
    await asyncio.wait_for(runner, 5)
    assert not client.is_running

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

"""Integration tests against a real Operaton engine (``OPERATON_URL``, default
``http://localhost:8080/engine-rest``). Run with ``pytest -m integration``."""

from __future__ import annotations

import os
import time
import uuid
from collections.abc import Callable, Iterator
from typing import Any

import httpx
import pytest

from operaton_external_task_client import (
    AsyncExternalTaskClient,
    BackOff,
    BpmnError,
    ClientConfig,
    ExternalTaskClient,
    Variable,
)

pytestmark = pytest.mark.integration

URL = os.environ.get("OPERATON_URL", "http://localhost:8080/engine-rest").rstrip("/")

BPMN = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
    xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
    targetNamespace="http://operaton.org/examples">
  <bpmn:process id="{key}" isExecutable="true" operaton:historyTimeToLive="P1D">
    <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
    <bpmn:serviceTask id="work" name="Do work" operaton:type="external" operaton:topic="{topic}">
      <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:boundaryEvent id="onError" attachedToRef="work">
      <bpmn:outgoing>f3</bpmn:outgoing>
      <bpmn:errorEventDefinition errorRef="err"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="endOk"/>
    <bpmn:sequenceFlow id="f3" sourceRef="onError" targetRef="endError"/>
    <bpmn:endEvent id="endOk"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
    <bpmn:endEvent id="endError"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
  </bpmn:process>
  <bpmn:error id="err" errorCode="INVALID"/>
</bpmn:definitions>
"""


def wait_for(fn: Callable[[], Any], what: str, timeout: float = 30.0) -> Any:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.1)
    raise AssertionError(f"timed out waiting for {what}")


class Engine:
    def __init__(self) -> None:
        self.http = httpx.Client(base_url=URL, timeout=30)

    def deploy(self) -> tuple[str, str]:
        suffix = uuid.uuid4().hex[:10]
        key, topic = f"py-it-{suffix}", f"py-it-topic-{suffix}"
        r = self.http.post(
            "/deployment/create",
            data={"deployment-name": key, "deploy-changed-only": "false"},
            files={"data": (f"{key}.bpmn", BPMN.format(key=key, topic=topic), "text/xml")},
        )
        r.raise_for_status()
        return key, topic

    def start(self, key: str, variables: dict[str, Any] | None = None) -> str:
        body = {"variables": {k: Variable.of(v).to_rest() for k, v in (variables or {}).items()}}
        r = self.http.post(f"/process-definition/key/{key}/start", json=body)
        r.raise_for_status()
        return str(r.json()["id"])

    def history_state(self, pid: str) -> str | None:
        r = self.http.get(f"/history/process-instance/{pid}")
        return r.json().get("state") if r.is_success else None

    def ended_activities(self, pid: str) -> set[str]:
        r = self.http.get("/history/activity-instance", params={"processInstanceId": pid})
        return {a["activityId"] for a in r.json() if a.get("endTime")}

    def variables(self, pid: str) -> dict[str, Any]:
        r = self.http.get("/history/variable-instance", params={"processInstanceId": pid})
        return {v["name"]: v for v in r.json()}

    def incidents(self, pid: str) -> list[dict[str, Any]]:
        return list(self.http.get("/incident", params={"processInstanceId": pid}).json())

    def external_tasks(self, pid: str) -> list[dict[str, Any]]:
        return list(self.http.get("/external-task", params={"processInstanceId": pid}).json())


@pytest.fixture(scope="module")
def engine() -> Iterator[Engine]:
    e = Engine()
    try:
        e.http.get("/engine").raise_for_status()
    except httpx.HTTPError as exc:  # fail loudly: the integration stage needs an engine
        pytest.fail(f"no Operaton engine at {URL} (set OPERATON_URL): {exc!r}")
    yield e
    e.http.close()


def config(**kw: Any) -> ClientConfig:
    defaults: dict[str, Any] = {
        "base_url": URL,
        "async_response_timeout": 2,
        "lock_duration": 30,
        "back_off": BackOff(initial=0.2, maximum=1),
    }
    defaults.update(kw)
    return ClientConfig(**defaults)


def test_complete_with_typed_variables(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key, {"amount": 42, "customer": "ACME", "doc": {"lines": [1, 2]}})
    seen: dict[str, Any] = {}

    def handler(task, service):  # type: ignore[no-untyped-def]
        seen.update(task.variables)
        seen["types"] = {n: v.type for n, v in task.typed_variables.items()}
        seen["activity"] = task.activity_id
        return {"total": task.variables["amount"] * 2, "approved": True, "ratio": 0.5}

    client = ExternalTaskClient(config())
    client.subscribe(topic, handler, process_definition_key=key)
    with client:
        wait_for(lambda: engine.history_state(pid) == "COMPLETED", "process completion")
    assert seen["amount"] == 42 and seen["customer"] == "ACME"
    assert seen["doc"] == {"lines": [1, 2]}
    assert seen["types"] == {"amount": "Integer", "customer": "String", "doc": "Json"}
    assert seen["activity"] == "work"
    assert "endOk" in engine.ended_activities(pid)
    out = engine.variables(pid)
    assert (out["total"]["type"], out["total"]["value"]) == ("Integer", 84)
    assert out["approved"]["value"] is True
    assert out["ratio"]["type"] == "Double"


def test_failure_decrements_retries_then_creates_incident(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key)
    attempts: list[int | None] = []

    def handler(task, service):  # type: ignore[no-untyped-def]
        attempts.append(task.retries)
        raise RuntimeError("downstream unavailable")

    client = ExternalTaskClient(config(default_retries=2, default_retry_timeout=0))
    client.subscribe(topic, handler)
    with client:
        wait_for(lambda: engine.incidents(pid), "incident after retries are used up")
    # first failure sets default_retries=2, the next one decrements to 1, then 0 (incident)
    assert attempts == [None, 2, 1]
    (task,) = engine.external_tasks(pid)
    assert task["retries"] == 0
    assert "downstream unavailable" in task["errorMessage"]
    assert engine.history_state(pid) == "ACTIVE"
    assert engine.incidents(pid)[0]["incidentType"] == "failedExternalTask"


def test_bpmn_error_reaches_boundary_event(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key)

    def handler(task, service):  # type: ignore[no-untyped-def]
        raise BpmnError("INVALID", "invoice rejected", {"reason": "no PO"})

    client = ExternalTaskClient(config())
    client.subscribe(topic, handler)
    with client:
        wait_for(lambda: engine.history_state(pid) == "COMPLETED", "process completion")
    ended = engine.ended_activities(pid)
    assert "endError" in ended and "endOk" not in ended
    assert engine.variables(pid)["reason"]["value"] == "no PO"


def test_service_actions_extend_lock_and_set_variables(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key)

    def handler(task, service):  # type: ignore[no-untyped-def]
        service.extend_lock(120)
        service.set_variables({"step": "locked-extended"})
        service.complete({"done": Variable.long(1)})

    client = ExternalTaskClient(config())
    client.subscribe(topic, handler)
    with client:
        wait_for(lambda: engine.history_state(pid) == "COMPLETED", "process completion")
    out = engine.variables(pid)
    assert out["step"]["value"] == "locked-extended"
    assert out["done"]["type"] == "Long"


def test_unlock_returns_task_to_the_pool(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key)
    calls: list[int] = []

    def handler(task, service):  # type: ignore[no-untyped-def]
        calls.append(1)
        if len(calls) == 1:
            service.unlock()
        else:
            service.complete()

    client = ExternalTaskClient(config())
    client.subscribe(topic, handler)
    with client:
        wait_for(lambda: engine.history_state(pid) == "COMPLETED", "process completion")
    assert len(calls) == 2


def test_filters_only_fetch_matching_tasks(engine: Engine) -> None:
    key, topic = engine.deploy()
    wanted = engine.start(key, {"region": "eu"})
    other = engine.start(key, {"region": "us"})
    client = ExternalTaskClient(config())
    client.subscribe(
        topic, lambda t, s: {}, process_variables={"region": "eu"}, variables=["region"]
    )
    with client:
        wait_for(lambda: engine.history_state(wanted) == "COMPLETED", "matching process")
    assert engine.history_state(other) == "ACTIVE"
    engine.http.delete(f"/process-instance/{other}")


async def test_async_client_completes(engine: Engine) -> None:
    key, topic = engine.deploy()
    pid = engine.start(key, {"n": 1})

    async def handler(task, service):  # type: ignore[no-untyped-def]
        return {"n2": task.variables["n"] + 1}

    client = AsyncExternalTaskClient(config())
    client.subscribe(topic, handler)
    async with client:
        import asyncio

        for _ in range(300):
            if engine.history_state(pid) == "COMPLETED":
                break
            await asyncio.sleep(0.1)
        else:
            raise AssertionError("process did not complete")
    assert engine.variables(pid)["n2"]["value"] == 2


def test_graceful_stop_finishes_running_task(engine: Engine) -> None:
    import threading

    key, topic = engine.deploy()
    pid = engine.start(key)
    started = threading.Event()

    def handler(task, service):  # type: ignore[no-untyped-def]
        started.set()
        time.sleep(1)
        return {"slow": True}

    client = ExternalTaskClient(config(async_response_timeout=1))
    client.subscribe(topic, handler)
    client.start()
    assert started.wait(20)
    assert client.stop(timeout=20) is True
    assert engine.history_state(pid) == "COMPLETED"

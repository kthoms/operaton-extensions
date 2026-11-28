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

"""Shared fixtures: a fake engine behind ``httpx.MockTransport`` for the unit tests."""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from operaton_external_task_client import ClientConfig

BASE = "http://engine.test/engine-rest"


def make_task(task_id: str = "t1", topic: str = "demo", **extra: Any) -> dict[str, Any]:
    task: dict[str, Any] = {
        "id": task_id,
        "topicName": topic,
        "workerId": "w",
        "processInstanceId": "pi-1",
        "processDefinitionId": "pd-1",
        "processDefinitionKey": "demo-process",
        "activityId": "Activity_1",
        "activityInstanceId": "ai-1",
        "executionId": "ex-1",
        "retries": None,
        "priority": 0,
        "lockExpirationTime": "2026-01-31T12:00:00.000+0000",
        "variables": {"amount": {"value": 12, "type": "Integer", "valueInfo": {}}},
        "extensionProperties": {},
    }
    task.update(extra)
    return task


class FakeEngine:
    """Records requests; ``fetchAndLock`` hands out queued tasks, then answers empty after a
    short pause (a long poll that timed out)."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.bodies: list[Any] = []
        self.tasks: list[dict[str, Any]] = []
        self.fetch_error: Callable[[], httpx.Response | Exception] | None = None
        self.fail_paths: dict[str, int] = {}
        self._lock = threading.Lock()

    def push(self, *tasks: dict[str, Any]) -> None:
        with self._lock:
            self.tasks.extend(tasks)

    def __call__(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content) if request.content else None
        with self._lock:
            self.requests.append(request)
            self.bodies.append(body)
        path = request.url.path.removeprefix("/engine-rest")
        for suffix, status in self.fail_paths.items():
            if path.endswith(suffix):
                return httpx.Response(
                    status, json={"type": "InvalidRequestException", "message": "boom"}
                )
        if path == "/external-task/fetchAndLock":
            if self.fetch_error is not None:
                result = self.fetch_error()
                if isinstance(result, Exception):
                    raise result
                return result
            with self._lock:
                n = body["maxTasks"]
                topics = {t["topicName"] for t in body["topics"]}
                out = [t for t in self.tasks if t["topicName"] in topics][:n]
                for t in out:
                    self.tasks.remove(t)
            if not out:
                time.sleep(0.02)
            return httpx.Response(200, json=out)
        if request.method == "GET":
            return httpx.Response(200, content=b"file-bytes")
        return httpx.Response(204)

    def calls(self, suffix: str) -> list[Any]:
        """Bodies of the requests whose path ends with ``suffix``."""
        with self._lock:
            return [
                b
                for r, b in zip(self.requests, self.bodies, strict=True)
                if r.url.path.endswith(suffix)
            ]

    def paths(self) -> list[str]:
        with self._lock:
            return [r.url.path.removeprefix("/engine-rest") for r in self.requests]


@pytest.fixture
def engine() -> FakeEngine:
    return FakeEngine()


@pytest.fixture
def config() -> ClientConfig:
    return ClientConfig(
        base_url=BASE,
        worker_id="w",
        max_tasks=5,
        async_response_timeout=0.05,
        lock_duration=30,
        default_retry_timeout=0.5,
        service_retries=0,
    )


def wait_until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("condition not met within timeout")

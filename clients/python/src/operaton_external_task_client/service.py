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
#
# Ported from https://github.com/camunda-community-hub/
# camunda-external-task-client-python3 (Apache-2.0); the original files carry no header,
# copyright the camunda-community-hub contributors. Modernised for Operaton.

"""The task service handed to handlers: complete, fail, BPMN error, extend lock, unlock ..."""

from __future__ import annotations

import threading
from collections.abc import Mapping
from typing import Any
from urllib.parse import quote

from . import _protocol as proto
from ._api import AsyncEngineApi, EngineApi
from .config import ClientConfig, Duration, millis
from .errors import ConfigurationError, TaskServiceError
from .task import ExternalTask
from .variables import encode_variables


class _ServiceBase:
    def __init__(self, task: ExternalTask, config: ClientConfig) -> None:
        self._task = task
        self._config = config
        self._lock = threading.Lock()
        self._finished: str | None = None

    @property
    def task(self) -> ExternalTask:
        return self._task

    @property
    def is_finished(self) -> bool:
        """True once the task was completed, failed, BPMN-errored or unlocked."""
        return self._finished is not None

    def _check_open(self, action: str) -> None:
        if self._finished is not None:
            raise TaskServiceError(
                f"cannot {action} task {self._task.id}: it was already {self._finished}"
            )

    def _finish(self, state: str) -> None:
        with self._lock:
            self._finished = state

    def _path(self, suffix: str) -> str:
        return f"/external-task/{quote(self._task.id, safe='')}/{suffix}"

    def _next_retries(self, retries: int | None) -> int:
        if retries is not None:
            return retries
        current = self._task.retries
        if current is None:
            return self._config.default_retries
        return max(current - 1, 0)

    def _failure(
        self,
        message: str,
        details: str | None,
        retries: int | None,
        retry_timeout: Duration | None,
    ) -> dict[str, Any]:
        self._check_open("report a failure for")
        return proto.failure_body(
            self._config.worker_id,
            message,
            details,
            self._next_retries(retries),
            self._config.default_retry_timeout if retry_timeout is None else retry_timeout,
        )

    def _variables_target(self, local: bool) -> str:
        """REST path of the variable collection; fail loudly if the task lacks the id."""
        if local:
            if not self._task.execution_id:
                raise ConfigurationError(f"task {self._task.id} has no execution id")
            return f"/execution/{quote(self._task.execution_id, safe='')}/localVariables"
        if not self._task.process_instance_id:
            raise ConfigurationError(f"task {self._task.id} has no process instance id")
        return f"/process-instance/{quote(self._task.process_instance_id, safe='')}/variables"

    def _extend(self, new_duration: Duration) -> dict[str, Any]:
        return {"workerId": self._config.worker_id, "newDuration": millis(new_duration, "duration")}


class TaskService(_ServiceBase):
    """Synchronous task service. One instance per task; the terminal calls are mutually
    exclusive (a task is completed, failed, BPMN-errored or unlocked once)."""

    def __init__(self, api: EngineApi, task: ExternalTask, config: ClientConfig) -> None:
        super().__init__(task, config)
        self._api = api

    def complete(
        self,
        variables: Mapping[str, Any] | None = None,
        local_variables: Mapping[str, Any] | None = None,
    ) -> None:
        """Complete the task; ``variables`` become process variables, ``local_variables`` are
        local to the task's execution. Values are plain Python values or ``Variable`` objects."""
        self._check_open("complete")
        body = proto.complete_body(self._config.worker_id, variables, local_variables)
        self._api.post(self._path("complete"), body)
        self._finish("completed")

    def handle_failure(
        self,
        message: str,
        details: str | None = None,
        retries: int | None = None,
        retry_timeout: Duration | None = None,
    ) -> None:
        """Report a failure. ``retries`` defaults to the task's retries minus one (or the
        configured ``default_retries`` on the first failure); at 0 the engine opens an incident.
        The task is fetched again after ``retry_timeout`` (default ``default_retry_timeout``)."""
        body = self._failure(message, details, retries, retry_timeout)
        self._api.post(self._path("failure"), body)
        self._finish("failed")

    def handle_bpmn_error(
        self,
        code: str,
        message: str | None = None,
        variables: Mapping[str, Any] | None = None,
    ) -> None:
        """Throw a BPMN error with ``code`` into the process."""
        self._check_open("throw a BPMN error for")
        self._api.post(
            self._path("bpmnError"),
            proto.bpmn_error_body(self._config.worker_id, code, message, variables),
        )
        self._finish("ended with a BPMN error")

    def extend_lock(self, new_duration: Duration) -> None:
        """Set the lock to expire ``new_duration`` from now (not added to the old one)."""
        self._check_open("extend the lock of")
        self._api.post(self._path("extendLock"), self._extend(new_duration))

    def unlock(self) -> None:
        """Give the task back so any worker can fetch it again."""
        self._check_open("unlock")
        self._api.post(self._path("unlock"), {})
        self._finish("unlocked")

    def set_retries(self, retries: int) -> None:
        """Set the remaining retries without failing the task."""
        self._check_open("set retries of")
        self._api.put(self._path("retries"), {"retries": retries})

    def set_variables(self, variables: Mapping[str, Any], *, local: bool = False) -> None:
        """Set process variables (or, with ``local``, variables of the task's execution) now,
        without completing the task."""
        path = self._variables_target(local)
        self._api.post(path, {"modifications": encode_variables(variables)})

    def download_file(self, name: str, *, local: bool = False) -> bytes:
        """Content of the File (or Bytes) variable ``name`` of this task's process instance."""
        path = self._variables_target(local)
        return self._api.get_bytes(f"{path}/{quote(name, safe='')}/data")


class AsyncTaskService(_ServiceBase):
    """Asyncio task service; same calls as :class:`TaskService`, as coroutines."""

    def __init__(self, api: AsyncEngineApi, task: ExternalTask, config: ClientConfig) -> None:
        super().__init__(task, config)
        self._api = api

    async def complete(
        self,
        variables: Mapping[str, Any] | None = None,
        local_variables: Mapping[str, Any] | None = None,
    ) -> None:
        self._check_open("complete")
        body = proto.complete_body(self._config.worker_id, variables, local_variables)
        await self._api.post(self._path("complete"), body)
        self._finish("completed")

    async def handle_failure(
        self,
        message: str,
        details: str | None = None,
        retries: int | None = None,
        retry_timeout: Duration | None = None,
    ) -> None:
        body = self._failure(message, details, retries, retry_timeout)
        await self._api.post(self._path("failure"), body)
        self._finish("failed")

    async def handle_bpmn_error(
        self,
        code: str,
        message: str | None = None,
        variables: Mapping[str, Any] | None = None,
    ) -> None:
        self._check_open("throw a BPMN error for")
        await self._api.post(
            self._path("bpmnError"),
            proto.bpmn_error_body(self._config.worker_id, code, message, variables),
        )
        self._finish("ended with a BPMN error")

    async def extend_lock(self, new_duration: Duration) -> None:
        self._check_open("extend the lock of")
        await self._api.post(self._path("extendLock"), self._extend(new_duration))

    async def unlock(self) -> None:
        self._check_open("unlock")
        await self._api.post(self._path("unlock"), {})
        self._finish("unlocked")

    async def set_retries(self, retries: int) -> None:
        self._check_open("set retries of")
        await self._api.put(self._path("retries"), {"retries": retries})

    async def set_variables(self, variables: Mapping[str, Any], *, local: bool = False) -> None:
        path = self._variables_target(local)
        await self._api.post(path, {"modifications": encode_variables(variables)})

    async def download_file(self, name: str, *, local: bool = False) -> bytes:
        path = self._variables_target(local)
        return await self._api.get_bytes(f"{path}/{quote(name, safe='')}/data")

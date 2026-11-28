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

"""HTTP access to the engine: one class for threads, one for asyncio."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Mapping, Sequence
from typing import Any

import httpx

from . import _protocol as proto
from .config import ClientConfig, seconds
from .errors import EngineUnavailableError
from .subscription import Subscription
from .task import ExternalTask

log = logging.getLogger("operaton_external_task_client")

_RETRY_BASE_DELAY = 0.25


def _headers(config: ClientConfig) -> dict[str, str]:
    return {"Accept": "application/json", **config.headers}


def _parse_tasks(response: httpx.Response) -> list[ExternalTask]:
    body = response.json()
    if not isinstance(body, list):
        raise EngineUnavailableError(f"fetchAndLock answered {type(body).__name__}, expected list")
    return [ExternalTask.from_rest(item) for item in body]


class EngineApi:
    """Synchronous REST access; thread-safe (``httpx.Client`` is)."""

    def __init__(self, config: ClientConfig, http_client: httpx.Client | None = None) -> None:
        self._config = config
        self._owns = http_client is None
        self._http = http_client or httpx.Client(
            auth=config.auth,
            headers=_headers(config),
            verify=config.verify,
            timeout=seconds(config.request_timeout, "request_timeout"),
        )

    def close(self) -> None:
        if self._owns:
            self._http.close()

    def _send(
        self,
        method: str,
        path: str,
        body: Any = None,
        *,
        retry: bool,
        timeout: float | None = None,
    ) -> httpx.Response:
        url = f"{self._config.root}{path}"
        attempts = (self._config.service_retries if retry else 0) + 1
        for attempt in range(1, attempts + 1):
            try:
                response = self._http.request(
                    method,
                    url,
                    json=body,
                    timeout=timeout if timeout is not None else httpx.USE_CLIENT_DEFAULT,
                )
            except httpx.TransportError as exc:
                if attempt < attempts:
                    log.warning("%s %s failed (%s), retrying", method, url, exc)
                    time.sleep(_RETRY_BASE_DELAY * 2 ** (attempt - 1))
                    continue
                raise EngineUnavailableError(f"{method} {url}: {exc!r}") from exc
            if response.status_code in proto.RETRYABLE_STATUS and attempt < attempts:
                time.sleep(_RETRY_BASE_DELAY * 2 ** (attempt - 1))
                continue
            if response.is_success:
                return response
            raise proto.engine_error(response)
        raise AssertionError("unreachable")  # pragma: no cover

    def fetch_and_lock(
        self, subscriptions: Sequence[Subscription], max_tasks: int
    ) -> list[ExternalTask]:
        body = proto.fetch_and_lock_body(self._config, subscriptions, max_tasks)
        poll = seconds(self._config.async_response_timeout, "async_response_timeout")
        timeout = poll + seconds(self._config.request_timeout, "request_timeout")
        return _parse_tasks(
            self._send("POST", "/external-task/fetchAndLock", body, retry=False, timeout=timeout)
        )

    def post(self, path: str, body: Mapping[str, Any]) -> None:
        self._send("POST", path, body, retry=True)

    def put(self, path: str, body: Mapping[str, Any]) -> None:
        self._send("PUT", path, body, retry=True)

    def get_bytes(self, path: str) -> bytes:
        return self._send("GET", path, retry=True).content


class AsyncEngineApi:
    """Asyncio REST access."""

    def __init__(self, config: ClientConfig, http_client: httpx.AsyncClient | None = None) -> None:
        self._config = config
        self._owns = http_client is None
        self._http = http_client or httpx.AsyncClient(
            auth=config.auth,
            headers=_headers(config),
            verify=config.verify,
            timeout=seconds(config.request_timeout, "request_timeout"),
        )

    async def aclose(self) -> None:
        if self._owns:
            await self._http.aclose()

    async def _send(
        self,
        method: str,
        path: str,
        body: Any = None,
        *,
        retry: bool,
        timeout: float | None = None,
    ) -> httpx.Response:
        url = f"{self._config.root}{path}"
        attempts = (self._config.service_retries if retry else 0) + 1
        for attempt in range(1, attempts + 1):
            try:
                response = await self._http.request(
                    method,
                    url,
                    json=body,
                    timeout=timeout if timeout is not None else httpx.USE_CLIENT_DEFAULT,
                )
            except httpx.TransportError as exc:
                if attempt < attempts:
                    log.warning("%s %s failed (%s), retrying", method, url, exc)
                    await asyncio.sleep(_RETRY_BASE_DELAY * 2 ** (attempt - 1))
                    continue
                raise EngineUnavailableError(f"{method} {url}: {exc!r}") from exc
            if response.status_code in proto.RETRYABLE_STATUS and attempt < attempts:
                await asyncio.sleep(_RETRY_BASE_DELAY * 2 ** (attempt - 1))
                continue
            if response.is_success:
                return response
            raise proto.engine_error(response)
        raise AssertionError("unreachable")  # pragma: no cover

    async def fetch_and_lock(
        self, subscriptions: Sequence[Subscription], max_tasks: int
    ) -> list[ExternalTask]:
        body = proto.fetch_and_lock_body(self._config, subscriptions, max_tasks)
        poll = seconds(self._config.async_response_timeout, "async_response_timeout")
        timeout = poll + seconds(self._config.request_timeout, "request_timeout")
        return _parse_tasks(
            await self._send(
                "POST", "/external-task/fetchAndLock", body, retry=False, timeout=timeout
            )
        )

    async def post(self, path: str, body: Mapping[str, Any]) -> None:
        await self._send("POST", path, body, retry=True)

    async def put(self, path: str, body: Mapping[str, Any]) -> None:
        await self._send("PUT", path, body, retry=True)

    async def get_bytes(self, path: str) -> bytes:
        return (await self._send("GET", path, retry=True)).content

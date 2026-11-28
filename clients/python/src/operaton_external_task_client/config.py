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

"""Client configuration."""

from __future__ import annotations

import os
import random
import socket
import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

import httpx

from .errors import ConfigurationError

Duration = float | int | timedelta
"""A duration: seconds as a number, or a :class:`datetime.timedelta`."""

DEFAULT_BASE_URL = "http://localhost:8080/engine-rest"


def seconds(value: Duration, name: str) -> float:
    """Return ``value`` as seconds; reject negatives and non-numbers."""
    if isinstance(value, timedelta):
        result = value.total_seconds()
    elif isinstance(value, bool) or not isinstance(value, int | float):
        raise ConfigurationError(f"{name} must be seconds or a timedelta, got {value!r}")
    else:
        result = float(value)
    if result < 0:
        raise ConfigurationError(f"{name} must not be negative, got {value!r}")
    return result


def millis(value: Duration, name: str) -> int:
    """Return ``value`` as whole milliseconds."""
    return round(seconds(value, name) * 1000)


@dataclass(frozen=True)
class BackOff:
    """Exponential back-off between failed polls of the engine.

    The delay after the n-th consecutive error is ``min(initial * factor**(n-1), maximum)``,
    randomised by ``+/- jitter`` (a fraction). A successful poll resets the sequence.
    """

    initial: float = 1.0
    factor: float = 2.0
    maximum: float = 60.0
    jitter: float = 0.1

    def __post_init__(self) -> None:
        if self.initial <= 0 or self.maximum < self.initial:
            raise ConfigurationError("back-off needs 0 < initial <= maximum")
        if self.factor < 1:
            raise ConfigurationError("back-off factor must be >= 1")
        if not 0 <= self.jitter < 1:
            raise ConfigurationError("back-off jitter must be in [0, 1)")

    def delay(self, consecutive_errors: int) -> float:
        """Delay in seconds after ``consecutive_errors`` (>= 1) failed polls in a row."""
        base = min(self.initial * self.factor ** max(consecutive_errors - 1, 0), self.maximum)
        if self.jitter:
            base *= 1 + random.uniform(-self.jitter, self.jitter)
        return max(base, 0.0)


def default_worker_id() -> str:
    """A worker id that is unique per client instance: host, pid and a random suffix."""
    return f"operaton-py-{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"


@dataclass(frozen=True)
class ClientConfig:
    """Configuration of an :class:`ExternalTaskClient` / :class:`AsyncExternalTaskClient`.

    All durations are seconds (a number) or a ``timedelta``.

    Attributes:
        base_url: REST root of the engine, e.g. ``http://localhost:8080/engine-rest``.
        worker_id: identifies this worker as the lock owner; unique per worker process. Generated
            when omitted.
        max_tasks: maximum number of tasks fetched (and locked) per request.
        handler_concurrency: maximum number of handlers running at once; defaults to ``max_tasks``.
            The client never fetches more tasks than there are free handler slots.
        async_response_timeout: long-polling timeout; the engine holds a fetch request this long
            when there is no task. ``0`` disables long polling (the client then waits
            ``idle_interval`` between empty polls).
        lock_duration: default lock duration of subscriptions.
        use_priority: fetch tasks in priority order.
        include_extension_properties: fetch the BPMN ``operaton:properties`` of the task.
        default_retries: retries set on the first failure of a task that has no retries yet; later
            failures decrement the engine's value (at ``0`` the engine creates an incident).
        default_retry_timeout: delay before a failed task is fetched again.
        back_off: delay between polls after errors.
        idle_interval: wait between empty polls when long polling is off.
        auth: :class:`BasicAuth`, :class:`BearerAuth` or any ``httpx.Auth``.
        headers: extra headers sent with every request.
        request_timeout: timeout of ordinary requests (everything but the long poll, which gets
            ``async_response_timeout`` plus this value).
        verify: TLS verification (``True``, ``False``, or a CA bundle path / ``ssl.SSLContext``).
        service_retries: how often task service calls are retried on connection errors, timeouts and
            502/503/504; ``0`` disables.
    """

    base_url: str = DEFAULT_BASE_URL
    worker_id: str = field(default_factory=default_worker_id)
    max_tasks: int = 10
    handler_concurrency: int | None = None
    async_response_timeout: Duration = 30.0
    lock_duration: Duration = 60.0
    use_priority: bool = True
    include_extension_properties: bool = True
    default_retries: int = 3
    default_retry_timeout: Duration = 10.0
    back_off: BackOff = field(default_factory=BackOff)
    idle_interval: Duration = 1.0
    auth: httpx.Auth | None = None
    headers: Mapping[str, str] = field(default_factory=dict)
    request_timeout: Duration = 30.0
    verify: Any = True
    service_retries: int = 2

    def __post_init__(self) -> None:
        if not self.base_url.startswith(("http://", "https://")):
            raise ConfigurationError(f"base_url must be an http(s) URL, got {self.base_url!r}")
        if not self.worker_id:
            raise ConfigurationError("worker_id must not be empty")
        if self.max_tasks < 1:
            raise ConfigurationError("max_tasks must be >= 1")
        if self.handler_concurrency is not None and self.handler_concurrency < 1:
            raise ConfigurationError("handler_concurrency must be >= 1")
        if self.default_retries < 0 or self.service_retries < 0:
            raise ConfigurationError("default_retries and service_retries must be >= 0")
        for name in (
            "async_response_timeout",
            "lock_duration",
            "default_retry_timeout",
            "idle_interval",
            "request_timeout",
        ):
            seconds(getattr(self, name), name)
        if millis(self.lock_duration, "lock_duration") < 1:
            raise ConfigurationError("lock_duration must be positive")

    @property
    def root(self) -> str:
        """``base_url`` without a trailing slash."""
        return self.base_url.rstrip("/")

    @property
    def concurrency(self) -> int:
        return self.handler_concurrency or self.max_tasks

    def __repr__(self) -> str:
        return (
            f"ClientConfig(base_url={self.base_url!r}, worker_id={self.worker_id!r}, "
            f"max_tasks={self.max_tasks}, auth={self.auth!r})"
        )

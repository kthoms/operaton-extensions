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

"""Request bodies and error handling of the external task REST API (no I/O)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

import httpx

from .config import ClientConfig, Duration, millis
from .errors import EngineError
from .subscription import Subscription
from .variables import encode_variables

#: The engine stores error messages in a 666 character column.
MAX_ERROR_MESSAGE = 666

RETRYABLE_STATUS = frozenset({502, 503, 504})


def fetch_and_lock_body(
    config: ClientConfig, subscriptions: Sequence[Subscription], max_tasks: int
) -> dict[str, Any]:
    default_lock = millis(config.lock_duration, "lock_duration")
    return {
        "workerId": config.worker_id,
        "maxTasks": max_tasks,
        "usePriority": config.use_priority,
        "asyncResponseTimeout": millis(config.async_response_timeout, "async_response_timeout"),
        "topics": [
            s.to_rest(
                default_lock,
                None if s.lock_duration is None else millis(s.lock_duration, "lock_duration"),
                config.include_extension_properties,
            )
            for s in subscriptions
        ],
    }


def complete_body(
    worker_id: str,
    variables: Mapping[str, Any] | None,
    local_variables: Mapping[str, Any] | None,
) -> dict[str, Any]:
    return {
        "workerId": worker_id,
        "variables": encode_variables(variables),
        "localVariables": encode_variables(local_variables),
    }


def failure_body(
    worker_id: str,
    message: str,
    details: str | None,
    retries: int,
    retry_timeout: Duration,
) -> dict[str, Any]:
    if retries < 0:
        raise ValueError(f"retries must be >= 0, got {retries}")
    body: dict[str, Any] = {
        "workerId": worker_id,
        "errorMessage": message[:MAX_ERROR_MESSAGE],
        "retries": retries,
        "retryTimeout": millis(retry_timeout, "retry_timeout"),
    }
    if details:
        body["errorDetails"] = details
    return body


def bpmn_error_body(
    worker_id: str,
    code: str,
    message: str | None,
    variables: Mapping[str, Any] | None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "workerId": worker_id,
        "errorCode": code,
        "variables": encode_variables(variables),
    }
    if message:
        body["errorMessage"] = message[:MAX_ERROR_MESSAGE]
    return body


def engine_error(response: httpx.Response) -> EngineError:
    """The :class:`EngineError` for a non-2xx response."""
    error_type: str | None = None
    message = response.reason_phrase or "no message"
    try:
        body = response.json()
    except ValueError:
        text = response.text.strip()
        if text:
            message = text[:500]
    else:
        if isinstance(body, Mapping):
            error_type = body.get("type")
            message = str(body.get("message") or message)
    request = response.request
    return EngineError(
        response.status_code,
        message,
        error_type=error_type,
        method=request.method,
        url=str(request.url),
    )

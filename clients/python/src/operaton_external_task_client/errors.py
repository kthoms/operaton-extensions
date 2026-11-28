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

"""Exceptions raised by the client."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any


class OperatonError(Exception):
    """Base class of every error raised by this package."""


class ConfigurationError(OperatonError, ValueError):
    """The client, a subscription or a call was configured with an invalid value."""


class EngineError(OperatonError):
    """The engine answered with an error status.

    ``status`` is the HTTP status, ``error_type`` and ``message`` come from the engine's JSON
    error body (``type`` and ``message``) when there is one.
    """

    def __init__(
        self,
        status: int,
        message: str,
        *,
        error_type: str | None = None,
        method: str | None = None,
        url: str | None = None,
    ) -> None:
        self.status = status
        self.message = message
        self.error_type = error_type
        self.method = method
        self.url = url
        where = f" ({method} {url})" if method and url else ""
        kind = f" {error_type}:" if error_type else ""
        super().__init__(f"engine answered {status}{kind} {message}{where}")


class EngineUnavailableError(OperatonError):
    """The engine could not be reached (connection refused, timeout, TLS error ...)."""


class TaskServiceError(OperatonError):
    """A task service call was made in a state where it is not allowed.

    For example completing a task that was already completed or reported as failed.
    """


class VariableError(OperatonError, ValueError):
    """A value cannot be represented as (or decoded from) an Operaton typed variable."""


class BpmnError(OperatonError):
    """Raise from a handler to report a BPMN error (``handle_bpmn_error``).

    The engine routes it to a matching error boundary event or error event sub process.
    """

    def __init__(
        self,
        code: str,
        message: str | None = None,
        variables: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(f"{code}: {message}" if message else code)
        self.code = code
        self.message = message
        self.variables = dict(variables) if variables else {}


class TaskFailure(OperatonError):
    """Raise from a handler to report a failure with explicit retry settings.

    Any other exception raised by a handler is reported as a failure too, with the retries
    derived from the task (see :meth:`TaskService.handle_failure`).
    """

    def __init__(
        self,
        message: str,
        details: str | None = None,
        retries: int | None = None,
        retry_timeout: float | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.details = details
        self.retries = retries
        self.retry_timeout = retry_timeout

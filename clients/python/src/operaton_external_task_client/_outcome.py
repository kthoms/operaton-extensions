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

"""What a handler's return value or exception means for the task."""

from __future__ import annotations

import traceback
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from .errors import BpmnError, TaskFailure


@dataclass(frozen=True)
class Failure:
    message: str
    details: str | None
    retries: int | None
    retry_timeout: float | None


def classify(exc: BaseException) -> BpmnError | Failure:
    """A :class:`BpmnError` is a BPMN error; everything else is a failure with context."""
    if isinstance(exc, BpmnError):
        return exc
    if isinstance(exc, TaskFailure):
        return Failure(exc.message, exc.details, exc.retries, exc.retry_timeout)
    text = str(exc)
    message = f"{type(exc).__name__}: {text}" if text else type(exc).__name__
    details = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    return Failure(message, details, None, None)


def result_variables(result: Any) -> Mapping[str, Any] | None:
    """A handler may return a mapping of variables to complete the task with, or ``None``."""
    if result is None or isinstance(result, Mapping):
        return result
    raise TypeError(
        "a handler must return None or a mapping of variables (or complete the task through "
        f"the task service), got {type(result).__name__}"
    )

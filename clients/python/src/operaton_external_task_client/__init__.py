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

"""External task client for Operaton.

Sync (threads) and asyncio workers that fetch and lock external tasks over the engine's REST API,
run a handler per task and report the result. See the project README for a tour.
"""

from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version

from .aio import AsyncExternalTaskClient, AsyncHandler
from .auth import BasicAuth, BearerAuth
from .client import ExternalTaskClient, Handler
from .config import BackOff, ClientConfig, Duration
from .errors import (
    BpmnError,
    ConfigurationError,
    EngineError,
    EngineUnavailableError,
    OperatonError,
    TaskFailure,
    TaskServiceError,
    VariableError,
)
from .service import AsyncTaskService, TaskService
from .subscription import Subscription
from .task import ExternalTask
from .variables import Variable

try:
    __version__ = version("operaton-external-task-client")
except PackageNotFoundError:  # pragma: no cover - running from a source tree without install
    __version__ = "0.0.0"

__all__ = [
    "AsyncExternalTaskClient",
    "AsyncHandler",
    "AsyncTaskService",
    "BackOff",
    "BasicAuth",
    "BearerAuth",
    "BpmnError",
    "ClientConfig",
    "ConfigurationError",
    "Duration",
    "EngineError",
    "EngineUnavailableError",
    "ExternalTask",
    "ExternalTaskClient",
    "Handler",
    "OperatonError",
    "Subscription",
    "TaskFailure",
    "TaskService",
    "TaskServiceError",
    "Variable",
    "VariableError",
    "__version__",
]

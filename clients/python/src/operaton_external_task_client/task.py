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

"""The external task as fetched from the engine."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from .variables import Variable, decode_variables, parse_date


@dataclass(frozen=True)
class ExternalTask:
    """A locked external task.

    ``variables`` holds the decoded Python values, ``typed_variables`` the :class:`Variable`
    objects (type and value info included). ``raw`` is the unmodified engine JSON.
    """

    id: str
    topic_name: str
    worker_id: str | None
    process_instance_id: str | None
    process_definition_id: str | None
    process_definition_key: str | None
    process_definition_version_tag: str | None
    activity_id: str | None
    activity_instance_id: str | None
    execution_id: str | None
    business_key: str | None
    tenant_id: str | None
    priority: int
    retries: int | None
    error_message: str | None
    lock_expiration_time: datetime | None
    extension_properties: Mapping[str, str]
    typed_variables: Mapping[str, Variable]
    raw: Mapping[str, Any] = field(repr=False)

    @property
    def variables(self) -> dict[str, Any]:
        """Variable values by name."""
        return {name: v.value for name, v in self.typed_variables.items()}

    def get_variable(self, name: str, default: Any = None) -> Any:
        """Value of variable ``name``, or ``default`` if the task has no such variable."""
        v = self.typed_variables.get(name)
        return default if v is None else v.value

    def get_typed_variable(self, name: str) -> Variable | None:
        return self.typed_variables.get(name)

    def get_extension_property(self, name: str, default: str | None = None) -> str | None:
        return self.extension_properties.get(name, default)

    @classmethod
    def from_rest(cls, body: Mapping[str, Any]) -> ExternalTask:
        """Build from one element of the ``fetchAndLock`` response."""
        lock = body.get("lockExpirationTime")
        props = body.get("extensionProperties") or {}
        if isinstance(props, list):  # engine versions answering a name/value list
            props = {p["name"]: p.get("value") for p in props}
        return cls(
            id=body["id"],
            topic_name=body["topicName"],
            worker_id=body.get("workerId"),
            process_instance_id=body.get("processInstanceId"),
            process_definition_id=body.get("processDefinitionId"),
            process_definition_key=body.get("processDefinitionKey"),
            process_definition_version_tag=body.get("processDefinitionVersionTag"),
            activity_id=body.get("activityId"),
            activity_instance_id=body.get("activityInstanceId"),
            execution_id=body.get("executionId"),
            business_key=body.get("businessKey"),
            tenant_id=body.get("tenantId"),
            priority=int(body.get("priority") or 0),
            retries=body.get("retries"),
            error_message=body.get("errorMessage"),
            lock_expiration_time=parse_date(lock) if lock else None,
            extension_properties=dict(props),
            typed_variables=decode_variables(body.get("variables")),
            raw=body,
        )

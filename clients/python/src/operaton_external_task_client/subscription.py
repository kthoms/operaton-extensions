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

"""Topic subscriptions."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from .config import Duration
from .errors import ConfigurationError


@dataclass(frozen=True, eq=False)
class Subscription:
    """What a worker fetches for one topic, and the handler that processes it.

    Created by ``subscribe()``; ``unsubscribe()`` removes it again. Unset (``None``) filters are
    not sent to the engine. ``variables=None`` fetches all process variables; ``[]`` fetches none.
    """

    topic: str
    handler: Callable[..., Any]
    lock_duration: Duration | None = None
    variables: Sequence[str] | None = None
    local_variables: bool = False
    business_key: str | None = None
    process_definition_id: str | None = None
    process_definition_ids: Sequence[str] = ()
    process_definition_key: str | None = None
    process_definition_keys: Sequence[str] = ()
    process_definition_version_tag: str | None = None
    tenant_id: str | None = None
    tenant_ids: Sequence[str] = ()
    without_tenant_id: bool = False
    process_variables: Mapping[str, Any] = field(default_factory=dict)
    include_extension_properties: bool | None = None
    _unsubscribe: Callable[[Subscription], None] | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        if not self.topic or not isinstance(self.topic, str):
            raise ConfigurationError("topic must be a non-empty string")
        if not callable(self.handler):
            raise ConfigurationError(f"handler for topic {self.topic!r} is not callable")
        for name in ("process_definition_ids", "process_definition_keys", "tenant_ids"):
            if isinstance(getattr(self, name), str):
                raise ConfigurationError(f"{name} must be a list of strings, not a single string")
        if isinstance(self.variables, str):
            raise ConfigurationError("variables must be a list of names, not a single string")
        if self.without_tenant_id and (self.tenant_id or self.tenant_ids):
            raise ConfigurationError("without_tenant_id cannot be combined with tenant ids")

    def unsubscribe(self) -> None:
        """Stop fetching this topic. Tasks already fetched are still processed."""
        if self._unsubscribe is None:
            raise ConfigurationError("subscription is not attached to a client")
        self._unsubscribe(self)

    def to_rest(
        self,
        default_lock_duration_ms: int,
        lock_duration_ms: int | None,
        include_extension_properties: bool,
    ) -> dict[str, Any]:
        """The ``topics`` element of the ``fetchAndLock`` request."""

        body: dict[str, Any] = {
            "topicName": self.topic,
            "lockDuration": lock_duration_ms
            if lock_duration_ms is not None
            else default_lock_duration_ms,
            "deserializeValues": False,
            "localVariables": self.local_variables,
            "includeExtensionProperties": (
                include_extension_properties
                if self.include_extension_properties is None
                else self.include_extension_properties
            ),
        }
        if self.variables is not None:
            body["variables"] = list(self.variables)
        if self.business_key:
            body["businessKey"] = self.business_key
        ids = _merge(self.process_definition_id, self.process_definition_ids)
        if len(ids) == 1:
            body["processDefinitionId"] = ids[0]
        elif ids:
            body["processDefinitionIdIn"] = ids
        keys = _merge(self.process_definition_key, self.process_definition_keys)
        if len(keys) == 1:
            body["processDefinitionKey"] = keys[0]
        elif keys:
            body["processDefinitionKeyIn"] = keys
        if self.process_definition_version_tag:
            body["processDefinitionVersionTag"] = self.process_definition_version_tag
        tenants = _merge(self.tenant_id, self.tenant_ids)
        if tenants:
            body["tenantIdIn"] = tenants
        if self.without_tenant_id:
            body["withoutTenantId"] = True
        if self.process_variables:
            body["processVariables"] = {
                k: _filter_value(v) for k, v in self.process_variables.items()
            }
        return body


def _merge(single: str | None, many: Sequence[str]) -> list[str]:
    merged = [single] if single else []
    merged.extend(x for x in many if x not in merged)
    return merged


def _filter_value(value: Any) -> Any:
    """Process variable filter values are sent untyped (the engine compares by equality)."""
    from .variables import Variable

    if isinstance(value, Variable):
        return value.to_rest()["value"]
    return value

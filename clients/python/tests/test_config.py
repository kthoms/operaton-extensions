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

from __future__ import annotations

from datetime import timedelta

import pytest

from operaton_external_task_client import (
    BackOff,
    BasicAuth,
    BearerAuth,
    ClientConfig,
    ConfigurationError,
    Subscription,
)
from operaton_external_task_client._protocol import fetch_and_lock_body


def test_defaults_and_generated_worker_ids_differ():
    a, b = ClientConfig(), ClientConfig()
    assert a.base_url == "http://localhost:8080/engine-rest"
    assert a.worker_id != b.worker_id
    assert a.concurrency == a.max_tasks == 10


@pytest.mark.parametrize(
    "kwargs",
    [
        {"base_url": "localhost:8080"},
        {"worker_id": ""},
        {"max_tasks": 0},
        {"handler_concurrency": 0},
        {"lock_duration": -1},
        {"lock_duration": 0},
        {"lock_duration": "5s"},
        {"default_retries": -1},
        {"async_response_timeout": True},
    ],
)
def test_invalid_config_is_rejected(kwargs):
    with pytest.raises(ConfigurationError):
        ClientConfig(**kwargs)


def test_backoff_grows_caps_and_validates():
    b = BackOff(initial=1, factor=2, maximum=5, jitter=0)
    assert [b.delay(n) for n in (1, 2, 3, 4, 10)] == [1, 2, 4, 5, 5]
    jittered = BackOff(initial=10, jitter=0.5)
    assert all(5 <= jittered.delay(1) <= 15 for _ in range(50))
    with pytest.raises(ConfigurationError):
        BackOff(initial=0)
    with pytest.raises(ConfigurationError):
        BackOff(factor=0.5)


def test_auth_reprs_hide_secrets():
    assert "secret" not in repr(BasicAuth("demo", "secret"))
    assert "t0k3n" not in repr(BearerAuth("t0k3n"))
    assert "secret" not in repr(ClientConfig(auth=BasicAuth("demo", "secret")))
    with pytest.raises(ValueError):
        BearerAuth("")


def test_fetch_body_defaults_and_overrides():
    cfg = ClientConfig(lock_duration=timedelta(minutes=1), async_response_timeout=20, worker_id="w")
    subs = [
        Subscription("a", print),
        Subscription("b", print, lock_duration=5, variables=["x"]),
    ]
    body = fetch_and_lock_body(cfg, subs, 3)
    assert body["workerId"] == "w"
    assert body["maxTasks"] == 3
    assert body["asyncResponseTimeout"] == 20000
    assert body["usePriority"] is True
    a, b = body["topics"]
    assert a["lockDuration"] == 60000 and "variables" not in a
    assert b["lockDuration"] == 5000 and b["variables"] == ["x"]
    assert a["deserializeValues"] is False


def test_subscription_filters():
    sub = Subscription(
        "t",
        print,
        variables=[],
        business_key="bk",
        process_definition_key="k1",
        process_definition_keys=["k1", "k2"],
        process_definition_id="id1",
        tenant_id="acme",
        tenant_ids=["beta"],
        process_variables={"region": "eu"},
        process_definition_version_tag="v1",
        local_variables=True,
    )
    body = sub.to_rest(1000, None, True)
    assert body["variables"] == []
    assert body["businessKey"] == "bk"
    assert body["processDefinitionKeyIn"] == ["k1", "k2"]
    assert body["processDefinitionId"] == "id1"
    assert body["tenantIdIn"] == ["acme", "beta"]
    assert body["processVariables"] == {"region": "eu"}
    assert body["processDefinitionVersionTag"] == "v1"
    assert body["localVariables"] is True
    assert (
        Subscription("t", print, process_definition_key="k").to_rest(1, None, True)[
            "processDefinitionKey"
        ]
        == "k"
    )
    assert Subscription("t", print, without_tenant_id=True).to_rest(1, None, True)[
        "withoutTenantId"
    ]


def test_subscription_validation():
    with pytest.raises(ConfigurationError):
        Subscription("", print)
    with pytest.raises(ConfigurationError):
        Subscription("t", "nope")  # type: ignore[arg-type]
    with pytest.raises(ConfigurationError):
        Subscription("t", print, tenant_ids="acme")  # type: ignore[arg-type]
    with pytest.raises(ConfigurationError):
        Subscription("t", print, variables="x")  # type: ignore[arg-type]
    with pytest.raises(ConfigurationError):
        Subscription("t", print, tenant_id="a", without_tenant_id=True)
    with pytest.raises(ConfigurationError):
        Subscription("t", print).unsubscribe()

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

import httpx
import pytest

from operaton_external_task_client import (
    BasicAuth,
    BearerAuth,
    ClientConfig,
    ConfigurationError,
    EngineError,
    EngineUnavailableError,
    ExternalTask,
    TaskService,
    TaskServiceError,
    Variable,
)
from operaton_external_task_client._api import EngineApi

from .conftest import make_task


def service(engine, config, **task_extra):
    http = httpx.Client(transport=httpx.MockTransport(engine))
    task = ExternalTask.from_rest(make_task(**task_extra))
    return TaskService(EngineApi(config, http), task, config)


def test_task_model():
    t = ExternalTask.from_rest(
        make_task(
            businessKey="bk",
            tenantId="acme",
            variables={
                "j": {"value": '{"a":1}', "type": "Json"},
                "n": {"value": 1, "type": "Integer"},
            },
            extensionProperties={"k": "v"},
        )
    )
    assert t.variables == {"j": {"a": 1}, "n": 1}
    assert t.get_variable("missing", 9) == 9
    assert t.get_typed_variable("n").type == "Integer"  # type: ignore[union-attr]
    assert t.business_key == "bk" and t.tenant_id == "acme"
    assert t.get_extension_property("k") == "v"
    assert t.lock_expiration_time is not None and t.lock_expiration_time.year == 2026


def test_complete_sends_typed_variables(engine, config):
    svc = service(engine, config)
    svc.complete({"ok": True, "doc": {"a": 1}}, local_variables={"l": Variable.long(3)})
    (body,) = engine.calls("/external-task/t1/complete")
    assert body["workerId"] == "w"
    assert body["variables"]["ok"] == {"value": True, "type": "Boolean"}
    assert body["variables"]["doc"]["type"] == "Json"
    assert body["localVariables"] == {"l": {"value": 3, "type": "Long"}}
    assert svc.is_finished


def test_terminal_calls_are_exclusive(engine, config):
    svc = service(engine, config)
    svc.complete()
    for call in (svc.complete, svc.unlock, lambda: svc.handle_failure("x")):
        with pytest.raises(TaskServiceError, match="already completed"):
            call()
    with pytest.raises(TaskServiceError):
        svc.handle_bpmn_error("E")
    with pytest.raises(TaskServiceError):
        svc.extend_lock(5)
    assert len(engine.requests) == 1


def test_failure_retries_first_then_decrement_then_explicit(engine, config):
    service(engine, config).handle_failure("m", "d")
    service(engine, config, retries=3).handle_failure("m")
    service(engine, config, retries=0).handle_failure("m")
    service(engine, config, retries=3).handle_failure("m", retries=7, retry_timeout=2)
    first, second, floor, explicit = engine.calls("/failure")
    assert first["retries"] == 3 and first["errorDetails"] == "d"
    assert first["retryTimeout"] == 500
    assert second["retries"] == 2
    assert floor["retries"] == 0
    assert explicit["retries"] == 7 and explicit["retryTimeout"] == 2000
    assert "errorDetails" not in second


def test_failure_message_is_truncated(engine, config):
    service(engine, config).handle_failure("x" * 1000)
    assert len(engine.calls("/failure")[0]["errorMessage"]) == 666


def test_bpmn_error(engine, config):
    service(engine, config).handle_bpmn_error("OUT_OF_STOCK", "none left", {"n": 0})
    (body,) = engine.calls("/bpmnError")
    assert body["errorCode"] == "OUT_OF_STOCK"
    assert body["errorMessage"] == "none left"
    assert body["variables"] == {"n": {"value": 0, "type": "Integer"}}


def test_extend_lock_and_unlock(engine, config):
    svc = service(engine, config)
    svc.extend_lock(90)
    assert svc.is_finished is False
    svc.unlock()
    assert engine.calls("/extendLock") == [{"workerId": "w", "newDuration": 90000}]
    assert engine.calls("/unlock") == [{}]
    assert svc.is_finished


def test_set_variables_process_and_local(engine, config):
    svc = service(engine, config)
    svc.set_variables({"a": 1})
    svc.set_variables({"b": "x"}, local=True)
    assert engine.paths() == [
        "/process-instance/pi-1/variables",
        "/execution/ex-1/localVariables",
    ]
    assert engine.bodies[0] == {"modifications": {"a": {"value": 1, "type": "Integer"}}}
    assert svc.is_finished is False


def test_set_variables_without_ids_fails_loudly(engine, config):
    svc = service(engine, config, processInstanceId=None, executionId=None)
    with pytest.raises(ConfigurationError):
        svc.set_variables({"a": 1})
    with pytest.raises(ConfigurationError):
        svc.set_variables({"a": 1}, local=True)


def test_set_retries_and_download(engine, config):
    svc = service(engine, config)
    svc.set_retries(2)
    assert engine.requests[0].method == "PUT"
    assert svc.download_file("doc") == b"file-bytes"
    assert engine.paths()[-1] == "/process-instance/pi-1/variables/doc/data"


def test_engine_error_has_status_type_message(engine, config):
    engine.fail_paths["/complete"] = 404
    svc = service(engine, config)
    with pytest.raises(EngineError) as info:
        svc.complete()
    assert info.value.status == 404
    assert info.value.error_type == "InvalidRequestException"
    assert info.value.message == "boom"
    assert "POST" in str(info.value)
    assert svc.is_finished is False


def test_retries_transient_errors_then_succeeds():
    calls = []

    def flaky(request):
        calls.append(1)
        if len(calls) < 3:
            return httpx.Response(503)
        return httpx.Response(204)

    cfg = ClientConfig(worker_id="w", service_retries=2)
    svc = TaskService(
        EngineApi(cfg, httpx.Client(transport=httpx.MockTransport(flaky))),
        ExternalTask.from_rest(make_task()),
        cfg,
    )
    svc.complete()
    assert len(calls) == 3


def test_connection_error_becomes_engine_unavailable():
    def refuse(request):
        raise httpx.ConnectError("refused")

    cfg = ClientConfig(worker_id="w", service_retries=0)
    svc = TaskService(
        EngineApi(cfg, httpx.Client(transport=httpx.MockTransport(refuse))),
        ExternalTask.from_rest(make_task()),
        cfg,
    )
    with pytest.raises(EngineUnavailableError):
        svc.complete()


def test_auth_headers_are_sent():
    seen = {}

    def capture(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json=[])

    for auth, expected_prefix in (
        (BasicAuth("demo", "demo"), "Basic "),
        (BearerAuth("tok"), "Bearer tok"),
    ):
        cfg = ClientConfig(worker_id="w", auth=auth, service_retries=0)
        api = EngineApi(cfg)  # owns a client; swap its transport
        api._http = httpx.Client(transport=httpx.MockTransport(capture), auth=auth)
        api.fetch_and_lock([], 1)
        assert seen["auth"].startswith(expected_prefix)
    calls = iter(["a", "b"])
    refreshing = BearerAuth(lambda: next(calls))
    client = httpx.Client(transport=httpx.MockTransport(capture), auth=refreshing)
    client.get("http://x/")
    assert seen["auth"] == "Bearer a"
    client.get("http://x/")
    assert seen["auth"] == "Bearer b"

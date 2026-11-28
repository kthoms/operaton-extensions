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
"""End-to-end test of the example worker (``example/``) against a real engine."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import httpx
import pytest

from .test_integration import URL, wait_for

pytestmark = pytest.mark.integration

EXAMPLE = Path(__file__).resolve().parents[1] / "example"


def load(name: str):  # type: ignore[no-untyped-def]
    spec = importlib.util.spec_from_file_location(f"example_{name}", EXAMPLE / f"{name}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_invoice_example_end_to_end() -> None:
    worker = load("worker")
    deploy = load("deploy_and_start")
    with httpx.Client(base_url=URL, timeout=30) as http:
        ids = deploy.deploy_and_start(URL)
        client = worker.build_client(URL)
        with client:
            for pid in ids:
                wait_for(
                    lambda pid=pid: (
                        http.get(f"/history/process-instance/{pid}").json()["state"] == "COMPLETED"
                    ),
                    f"instance {pid}",
                )
        small, large = ids

        def variables(pid: str) -> dict[str, object]:
            r = http.get("/history/variable-instance", params={"processInstanceId": pid})
            return {v["name"]: v["value"] for v in r.json()}

        def ended(pid: str) -> set[str]:
            r = http.get("/history/activity-instance", params={"processInstanceId": pid})
            return {a["activityId"] for a in r.json()}

        assert "invoiceProcessed" in ended(small)
        assert variables(small)["approved"] is True
        assert variables(small)["totalWithTax"] == 2166.4
        assert "invoiceRejected" in ended(large)
        assert variables(large)["rejectionReason"] == "limit exceeded"

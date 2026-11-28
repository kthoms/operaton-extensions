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
"""Deploy the invoice process and start sample invoices (REST API only).

Usage: ``python deploy_and_start.py`` (environment: ``OPERATON_URL``).
"""

from __future__ import annotations

import os
from pathlib import Path

import httpx

from operaton_external_task_client import Variable

HERE = Path(__file__).parent

INVOICES = [
    {
        "invoiceNumber": "INV-1001",
        "customer": "ACME Corp",
        "lines": [
            {"description": "Consulting", "quantity": 10, "unitPrice": 150},
            {"description": "Travel", "quantity": 1, "unitPrice": 320.5},
        ],
    },
    {
        "invoiceNumber": "INV-1002",
        "customer": "Globex",
        "lines": [{"description": "Annual licence", "quantity": 1, "unitPrice": 25000}],
    },
]


def deploy_and_start(base_url: str) -> list[str]:
    with httpx.Client(base_url=base_url, timeout=30) as http:
        bpmn = HERE / "invoice-processing.bpmn"
        http.post(
            "/deployment/create",
            data={"deployment-name": "invoice-processing", "deploy-changed-only": "true"},
            files={"data": (bpmn.name, bpmn.read_bytes(), "text/xml")},
        ).raise_for_status()
        ids = []
        for invoice in INVOICES:
            body = {
                "businessKey": invoice["invoiceNumber"],
                "variables": {k: Variable.of(v).to_rest() for k, v in invoice.items()},
            }
            r = http.post("/process-definition/key/invoice-processing/start", json=body)
            r.raise_for_status()
            ids.append(r.json()["id"])
        return ids


if __name__ == "__main__":
    url = os.environ.get("OPERATON_URL", "http://localhost:8080/engine-rest")
    for pid in deploy_and_start(url):
        print(f"started invoice-processing instance {pid}")

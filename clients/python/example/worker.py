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
"""Invoice-processing worker: validates invoices and approves or rejects them.

Run with ``python worker.py`` (environment: ``OPERATON_URL``, optional ``OPERATON_USER`` and
``OPERATON_PASSWORD``). The process model is ``invoice-processing.bpmn``.
"""

from __future__ import annotations

import logging
import os
from decimal import Decimal

from operaton_external_task_client import (
    BasicAuth,
    BpmnError,
    ClientConfig,
    ExternalTask,
    ExternalTaskClient,
    TaskFailure,
    TaskService,
)

TOPIC = "process-invoice"
APPROVAL_LIMIT = Decimal("10000")


def process_invoice(task: ExternalTask, service: TaskService) -> dict[str, object]:
    """Approve an invoice, or reject it with the BPMN error ``INVOICE_REJECTED``.

    Input variables: ``invoiceNumber`` (str), ``amount`` (number), ``customer`` (str),
    ``lines`` (JSON list of ``{"description", "quantity", "unitPrice"}``).
    Output variables: ``approved``, ``totalWithTax`` (double), ``approvalNote``.
    """
    number = task.get_variable("invoiceNumber")
    lines = task.get_variable("lines") or []
    if number is None:
        # a data problem retrying cannot fix: let it fail until the incident is raised
        raise TaskFailure("variable invoiceNumber is missing", retries=0)

    net = sum(
        (Decimal(str(line["quantity"])) * Decimal(str(line["unitPrice"])) for line in lines),
        Decimal(0),
    ) or Decimal(str(task.get_variable("amount", 0)))
    if net <= 0:
        raise BpmnError("INVOICE_REJECTED", f"invoice {number} has no positive amount")
    if net > APPROVAL_LIMIT:
        raise BpmnError(
            "INVOICE_REJECTED",
            f"invoice {number}: {net} exceeds the automatic approval limit {APPROVAL_LIMIT}",
            {"rejectionReason": "limit exceeded"},
        )
    total = float((net * Decimal("1.19")).quantize(Decimal("0.01")))
    logging.getLogger("invoice-worker").info("invoice %s approved, total %.2f", number, total)
    return {
        "approved": True,
        "totalWithTax": total,
        "approvalNote": f"approved automatically by {service.task.worker_id}",
    }


def build_client(url: str | None = None) -> ExternalTaskClient:
    user = os.environ.get("OPERATON_USER")
    config = ClientConfig(
        base_url=url or os.environ.get("OPERATON_URL", "http://localhost:8080/engine-rest"),
        max_tasks=5,
        lock_duration=60,
        async_response_timeout=20,
        auth=BasicAuth(user, os.environ.get("OPERATON_PASSWORD", "")) if user else None,
    )
    client = ExternalTaskClient(config)
    client.subscribe(
        TOPIC,
        process_invoice,
        variables=["invoiceNumber", "amount", "customer", "lines"],
        process_definition_key="invoice-processing",
    )
    return client


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    build_client().run_forever()  # Ctrl-C / SIGTERM: stops fetching, finishes running tasks

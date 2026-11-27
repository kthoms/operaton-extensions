# Python External Task Client — example

An invoice-processing worker: a process model sends every incoming invoice to the external task
topic `process-invoice`; the Python worker totals the lines, approves small invoices and rejects
large ones with a BPMN error that the model routes to a "rejected" end event.

## What it shows

- subscribing with a variable list and a process definition filter;
- typed variables (a JSON `lines` list in, a `Double` total out);
- a handler that returns variables (complete), and raises `BpmnError` and `TaskFailure`;
- graceful shutdown on Ctrl-C.

## Process model

`invoice-processing.bpmn`: start event "Invoice received" -> external task "Process invoice"
(topic `process-invoice`) -> end event "Invoice processed"; an error boundary event
(`INVOICE_REJECTED`) on the task leads to the end event "Invoice rejected". Open it in
Cockpit (Deployments) or bpmn.io to see the diagram.

## Prerequisites

Python 3.10+, Docker.

## Run it

```bash
docker compose up -d --wait        # Operaton 2.2.2 on http://localhost:8080 (demo / demo)
python3 -m venv .venv && . .venv/bin/activate
pip install -e ..                  # or: pip install -r requirements.txt
python deploy_and_start.py         # deploys the BPMN, starts INV-1001 (small) and INV-1002 (large)
python worker.py                   # processes both, Ctrl-C to stop
```

Cockpit: http://localhost:8080/operaton/app/cockpit (demo / demo). Set `OPERATON_URL` for
another engine and `OPERATON_USER` / `OPERATON_PASSWORD` if the REST API is protected.

## Walkthrough

- INV-1001 (1,500 + 320.50 net): approved; process ends in "Invoice processed" with
  `approved=true`, `totalWithTax=2166.40`.
- INV-1002 (25,000): exceeds the limit; `BpmnError("INVOICE_REJECTED")` is thrown, the process
  ends in "Invoice rejected" and has `rejectionReason="limit exceeded"`.
- Stop the worker mid-way and restart it: unfinished tasks are fetched again once their lock
  expires.

## How it works

`worker.py`: `process_invoice` is the handler (the return value completes the task, a raised
`BpmnError` becomes `handle_bpmn_error`, any other exception becomes `handle_failure` with
retries); `build_client` configures and subscribes. `deploy_and_start.py` talks to the REST API.

## Tests

`tests/test_example_integration.py` in the parent project deploys this model and runs the worker
against a real engine (`pytest -m integration`, `OPERATON_URL`).

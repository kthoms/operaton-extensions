# Process Test Coverage — example

An invoice approval process (small invoices are booked directly, large ones need approval or are
rejected) whose tests must together execute every element of the model, enforced by
`ProcessTestCoverageExtension`.

## What it shows

- the process `invoice-approval` ([`bpmn/invoice-approval.bpmn`](src/main/resources/bpmn/invoice-approval.bpmn)),
  16 elements: 8 flow nodes and 8 sequence flows;
- three tests, one per path, with `withMinimumCoverage(1.0)`: remove one and the build fails
  with the coverage that is left;
- the generated report, `target/process-test-coverage/<test class>/index.html` (BPMN with covered
  elements in green, selectable per test method) and `coverage.json`.

Per-method coverage of the three tests: 56%, 81%, 69%; together 100%.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo); start "invoice-approval" with variable amount
```

## Test it

```bash
chmod +x ../../../mvnw
../../../mvnw verify          # coverage tests (embedded H2) and an application IT (PostgreSQL via Testcontainers, Docker required)
open target/process-test-coverage/*InvoiceApprovalCoverageIT/index.html
```

On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. The report loads bpmn-js
from a CDN; offline it shows the element lists only.

## How it works

- `InvoiceApprovalCoverageIT`: the extension, the thresholds and the three path tests.
- `CheckInvoiceDelegate`, `BookInvoiceDelegate`: the service tasks.
- `InvoiceApprovalApplicationIT`: starts the Spring Boot application on PostgreSQL and runs a small
  invoice, to show that the model is deployable in the application too.

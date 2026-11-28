# Custom Batch Plugin — example

After a tax data fix the stored totals of 1000 invoices in PostgreSQL are wrong; a process starts a custom batch that recalculates them in 20 jobs of 50 invoices, and the batch statistics show how far it is.

## What it shows

- A handler bean (`RecalculateInvoiceTotalsHandler`) registered by the starter: one dependency, no plugin code.
- Creating a batch from a service task (`StartRecalculationDelegate`), inside the transaction of the process instance, with `invocationsPerBatchJob(50)` and parallel jobs.
- Following the batch through `CustomBatches.progress` (the engine's batch statistics) and in Cockpit.
- An error path: an invoice id that does not exist fails its job; `failedJobs` reports the items, and after the data fix `retryFailedJobs` lets the batch finish.

## Process model

`src/main/resources/bpmn/invoice-recalculation.bpmn`: Invoice totals outdated, Start recalculation batch (service task), Batch started. Open it in bpmn.io or Cockpit.

## Prerequisites

JDK 21, Docker (Compose).

## Run it

```bash
chmod +x ../../../mvnw
docker compose up -d --wait        # PostgreSQL :5432 (operaton / operaton)
../../../mvnw spring-boot:run
# Cockpit: http://localhost:8080 (demo / demo), menu Batches
```

On startup the application seeds 1000 invoices with a total of 0 (`example.invoices`), starts the process (`example.auto-start=false` switches this off) and logs the progress once a second until the batch is finished.

## Walkthrough

Happy path: watch the log (`Batch 5: 7 of 20 jobs done, 0 failed (35 %)`) or the Batches view in Cockpit; at the end every invoice has `total = round(sum(quantity * unit_price) * (1 + tax_rate), 2)`.

Error path: create a batch with an invoice id that does not exist (the integration test does this with id 5000). The job with that id fails three times and raises an incident, the other 20 jobs complete, `failedJobs` lists the items of the failed job, and once the invoice exists `retryFailedJobs` lets the batch finish. A third test runs an item handler through Spring transactions: failing invoices are rolled back one by one, the others stay committed.

## How it works

- `RecalculateInvoiceTotalsHandler`: one SQL statement per job, in the engine's Spring transaction; throws if an invoice is missing.
- `StartRecalculationDelegate`: loads the ids, `CustomBatchBuilder.of(handler, ids).create()` inside the process instance's command, stores `batchId`.
- `InvoiceApplication`: sets `batchPollTime` to one second so a finished batch is noticed quickly.
- `application.yaml`: history time to live of the batch type through `operaton.extensions.plugin.custom-batch.history-time-to-live`.

## Run the tests

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL), Docker required
```

`InvoiceRecalculationIT` runs the process and the batch end to end on the job executor and checks every total; plus the error path and the item handler.

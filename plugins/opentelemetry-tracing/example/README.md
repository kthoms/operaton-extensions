# OpenTelemetry Tracing - example

An order fulfilment process: a Spring Boot application validates an order, lets sales approve large orders, charges the payment asynchronously and ships through an external task. Every step ends up in one Jaeger trace.

## What it shows

- the starter picking up the application's `OpenTelemetry` bean (OTLP/HTTP exporter to Jaeger);
- the span hierarchy: process > activities > job > activities, external task fetch and complete as children of the shipping task;
- the error path: a declined card is a BPMN error that routes to "Order cancelled" within the same trace.

Process model: [`src/main/resources/order-fulfilment.bpmn`](src/main/resources/order-fulfilment.bpmn) (open it in Cockpit or bpmn.io).

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL and Jaeger
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)   Jaeger: http://localhost:16686
```

Throwaway credentials: PostgreSQL `operaton`/`operaton`.

## Walkthrough

1. Start the process in Cockpit or from a Java shell: `startProcessInstanceByKey("order-fulfilment", "ORD-1", Map.of("orderTotal", 1500L, "card", "ok"))`.
2. Complete "Approve order" in Tasklist (only for totals above 1000).
3. Open Jaeger, service `order-fulfilment`, find the trace (tag `operaton.process.business_key=ORD-1`).
4. Start another with `"card", "declined"`: the trace ends in "Order cancelled".

## How it works

| Element | Code |
|---|---|
| Tracing setup | `TracingConfiguration` (SDK + OTLP exporter bean) |
| Validate order | `ValidateOrder` |
| Charge payment (async job, BPMN error) | `ChargePayment` |
| Ship order (external task) | `ShippingWorker`, polls through the engine API |

## Run the tests

```bash
../../../mvnw verify          # Testcontainers: PostgreSQL and Jaeger, Docker required
```

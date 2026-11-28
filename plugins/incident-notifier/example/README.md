# Incident Notifier Plugin - example

A payment process charges an order; when the payment gateway declines a large amount the service task fails, an incident is created and the on-call inbox (Mailpit) receives the alert with a Cockpit link.

## What it shows

- The starter (`incident-notifier-plugin-spring-boot-starter`) configured only through `application.yaml`.
- A failed job incident turned into a mail (and, in the test, a webhook POST) after the transaction committed.

## Process model

`src/main/resources/bpmn/payment-processing.bpmn`: Order received, Charge payment (async, one attempt, then incident), Order paid. Open it in bpmn.io or Cockpit for the picture.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait        # PostgreSQL :5432 (operaton/operaton), Mailpit SMTP :1025, UI :8025
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo), mail inbox: http://localhost:8025
```

## Walkthrough

Happy path: start `payment-processing` with business key `order-1` and variable `amount=250` (Cockpit or REST); the instance ends, no mail.
Error path: start it with `amount=5000`; after the single attempt fails an incident appears and the mail "[Operaton] 1 incident(s) in process payment-processing" arrives in Mailpit with the message, activity, instance id, business key and the Cockpit link.

## How it works

| Where | What |
|---|---|
| `payment-processing.bpmn`, `chargePayment` | async service task, `failedJobRetryTimeCycle R1/PT0S`, delegate `${paymentService}` |
| `PaymentService` | declines amounts above 1000 EUR by throwing |
| `application.yaml` | `operaton.extensions.plugin.incident-notifier.*` (SMTP to Mailpit, throttle 30 s, Cockpit base URL) |

## Run the tests

```bash
../../../mvnw verify               # Testcontainers (PostgreSQL, Mailpit); on Colima export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

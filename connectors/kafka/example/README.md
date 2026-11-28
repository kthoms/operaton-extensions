# Kafka connector — example

An order process driven by Kafka: an `order-placed` record starts it, a payment step approves or rejects the order, and an `order-confirmed` or `order-rejected` record is published after the engine transaction committed.

## What it shows

- an inbound subscription that starts the process, taking business key, variables and the `traceparent` header from the record (`application.yaml`);
- the `kafka-produce` connector publishing after commit, with process headers and the propagated trace context (`order-process.bpmn`);
- a poison record (not JSON) going to the dead-letter topic `order-placed.dlt`.

Process model: `src/main/resources/bpmn/order-process.bpmn` (start, charge payment, gateway, publish event, two end events); open it in bpmn.io or Cockpit to see it.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
chmod +x ../../../mvnw
docker compose up -d --wait   # PostgreSQL 5432 (operaton/operaton/operaton), Kafka 9092
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
```

Place an order and watch the result:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-placed \
  --property parse.key=true --property key.separator=:
# then type:  o-1:{"orderId":"o-1","amount":250,"customer":"alice"}
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-confirmed --from-beginning --property print.key=true --property print.headers=true
```

An amount above 1000 is rejected and ends in `order-rejected`; a value that is not JSON ends in `order-placed.dlt`.

## How it works

| Where | What |
|---|---|
| `application.yaml`, `operaton.extensions.connector.kafka` | broker and the `orders` subscription |
| `order-process.bpmn`, `Task_PublishConfirmed` / `Task_PublishRejected` | the `kafka-produce` connector |
| `PaymentService` | the payment step and the event payload |

## Test it

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL, Kafka) integration test, Docker required
```

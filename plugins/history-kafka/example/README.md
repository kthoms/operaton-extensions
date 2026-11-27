# History Kafka Plugin — example

A bank processes loan applications in Operaton; the engine's history is streamed to Kafka, and a
small consumer turns the events into an analytics view (instances started/completed/in flight,
outcomes, average step durations).

## What it shows

* The Spring Boot starter configuring the plugin (`application.yaml`): one topic `loan-history`, only
  `process-instance` and `activity-instance` events, synchronous send, fail on error.
* Events published only after commit: an application without a credit score fails in the delegate,
  rolls back and never reaches Kafka.
* A consumer joining `start` and `end` events (history events are deltas) and de-duplicating by event id.

Process model: [`loan-application.bpmn`](src/main/resources/bpmn/loan-application.bpmn) (PNG not
rendered yet; open it in Cockpit or bpmn.io): application received, check credit, then reject (high
risk), approve automatically (low risk, up to 10000) or review by an underwriter, who approves or rejects.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL on 5432, Kafka on 9092 (throwaway credentials operaton/operaton)
../../../mvnw spring-boot:run # run `chmod +x ../../../mvnw` first if needed
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
```

## Walkthrough

Start `loan-application` (Tasklist or REST) with variables `amount` (Long) and `creditScore` (Long):

* `creditScore` 400: rejected immediately.
* `amount` 5000, `creditScore` 780: approved automatically.
* `amount` 50000, `creditScore` 650: a task "Review application" for group `underwriters`; complete it with `approved=true|false`.
* no `creditScore`: the start fails and is rolled back.

Watch `GET http://localhost:8080/api/loan-analytics` (the view) and the topic:
`docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic loan-history --from-beginning`.

## How it works

* Plugin settings: [`application.yaml`](src/main/resources/application.yaml)
* Delegate with the failure path: [`CreditCheck`](src/main/java/org/operaton/extensions/plugin/historykafka/example/CreditCheck.java)
* Consumer thread: [`LoanHistoryConsumer`](src/main/java/org/operaton/extensions/plugin/historykafka/example/LoanHistoryConsumer.java)
* The view: [`LoanAnalytics`](src/main/java/org/operaton/extensions/plugin/historykafka/example/LoanAnalytics.java), served by `LoanAnalyticsController`

## Run the tests

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL + Kafka), Docker required
```

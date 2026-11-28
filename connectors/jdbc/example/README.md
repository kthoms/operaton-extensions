# JDBC connector - example

An order arrives for a customer: the process looks the customer up in the business database, checks that the customer
is active and the order is within the credit limit, and inserts the order, using `jdbc-query` and `jdbc-update`.
The business data lives in its own database (`business`) next to the engine's (`operaton`) on one PostgreSQL server.

## What it shows

- `jdbc-query` with `SINGLE_ROW` and `allowEmpty` (unknown customer is a business outcome, not an incident)
- `jdbc-update` returning the generated order id
- a second data source configured with `operaton.extensions.connector.jdbc.datasources.default.*`
- named parameters: an injection attempt in the customer id is just an unknown customer

## Process model

Source: [`src/main/resources/bpmn/order-intake.bpmn`](src/main/resources/bpmn/order-intake.bpmn) (open it in Cockpit or bpmn.io).

## Prerequisites

JDK 21, Docker.

## Run it

```bash
chmod +x ../../../mvnw
docker compose up -d --wait   # PostgreSQL with the databases operaton and business (initdb/01-business.sql)
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
```

Database credentials (throwaway): `operaton` / `operaton`, port 5432.

## Walkthrough

Start a process in Cockpit or with `curl` (REST is not enabled; use Cockpit's start dialog) with the variables
`customerId` (string) and `orderTotal` (a decimal, for example `1234.50` as Double/Long in Cockpit).

- `C-1000`, total within 5000.00: ends in "Order placed"; `SELECT * FROM sales_order` in the `business` database shows the row.
- `C-9999` (unknown) or `C-2000` (inactive): ends in "Rejected: customer unknown or inactive".
- `C-3000` with a total above 100.00: ends in "Rejected: credit limit exceeded".

## How it works

- Lookup and insert: the two service tasks in `order-intake.bpmn`.
- Data source: `src/main/resources/application.yaml`; the engine database is `spring.datasource`.
- The connector registers through the starter's auto-configuration; no Java code is needed.

## Run the tests

```bash
../../../mvnw verify          # Testcontainers integration tests, Docker required
```

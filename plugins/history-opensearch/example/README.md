# History OpenSearch Plugin — example

A shop runs its order fulfilment in Operaton; the engine's history is indexed into OpenSearch after every commit, and
a small search service answers "which orders did customer X place", "what is waiting in the warehouse" and "how many
orders are in which state, and how long do they take" from the indices (plus optional OpenSearch Dashboards).

## What it shows

* The Spring Boot starter configuring the plugin (`application.yaml`): monthly indices, background bulk writes every
  500 ms, variable values indexed, fail loudly.
* Current-state documents: an order is one document that is created when it starts and updated when it ends (state,
  end time, duration), and its tasks and variables are searchable on their own.
* Nothing is indexed for a rolled-back transaction: an order without a SKU fails in the delegate, rolls back and is
  never searchable.

Process model: [`order-fulfilment.bpmn`](src/main/resources/bpmn/order-fulfilment.bpmn) (PNG not rendered yet; open it
in Cockpit or bpmn.io): order received, check stock, then pack the order (warehouse) and ship it, or arrange a backorder
(purchasing) when the SKU is out of stock.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL on 5432, OpenSearch on 9200 (throwaway setup: no security, user operaton/operaton for PostgreSQL)
../../../mvnw spring-boot:run # run `chmod +x ../../../mvnw` first if needed
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
```

Optional dashboards: `docker compose --profile dashboards up -d --wait`, open http://localhost:5601, create the index
patterns `operaton-process-instances-*`, `operaton-tasks-*`, ... (time field `startTime`) and explore (Discover).

## Walkthrough

Start `order-fulfilment` (Tasklist or REST) with the variables `customer` (String), `sku` (String) and `amount` (Long):

```bash
curl -s -u demo:demo -H 'content-type: application/json' localhost:8080/engine-rest/process-definition/key/order-fulfilment/start -d \
 '{"variables":{"customer":{"value":"acme","type":"String"},"sku":{"value":"BOOK-42","type":"String"},"amount":{"value":120,"type":"Long"}}}'
```

* `sku` `BOOK-42`: waits at "Pack order" (group `warehouse`); complete it in Tasklist and the order ships.
* `sku` `OUT-7`: waits at "Arrange backorder" (group `purchasing`).
* no `sku`: the start fails and is rolled back; nothing reaches OpenSearch.

After about a second (index refresh) look at the search service:

```bash
curl -s 'localhost:8080/api/orders?customer=acme'      # the customer's orders: state, start/end time, duration, amount
curl -s localhost:8080/api/orders/summary              # orders per state and average duration
curl -s localhost:8080/api/orders/open-tasks           # tasks nobody has completed
curl -s 'localhost:9200/operaton-*/_search?q=processDefinitionKey:order-fulfilment&size=1&pretty'   # or straight from OpenSearch
```

## How it works

* Plugin settings: [`application.yaml`](src/main/resources/application.yaml)
* Delegates (stock check with the failure path, shipment): [`StockCheck`](src/main/java/org/operaton/extensions/plugin/historyopensearch/example/StockCheck.java), [`ShipOrder`](src/main/java/org/operaton/extensions/plugin/historyopensearch/example/ShipOrder.java)
* The queries: [`OrderSearch`](src/main/java/org/operaton/extensions/plugin/historyopensearch/example/OrderSearch.java), served by `OrderSearchController`

## Run the tests

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL + OpenSearch), Docker required
```

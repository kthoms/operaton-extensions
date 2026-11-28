# Micrometer Metrics Plugin — example

An order fulfillment process runs on Spring Boot and PostgreSQL; the plugin exposes its engine metrics, job backlog, incidents and
instance counters on `/actuator/prometheus`, Prometheus scrapes them and a provisioned Grafana dashboard shows them.

## What it shows

- The starter with a Prometheus registry: one dependency, properties under `operaton.extensions.plugin.micrometer-metrics`.
- Counters for started and ended orders, gauges for running orders, the job backlog and open incidents.
- An error path: an order with an unknown article fails its job without retries and shows up as an incident.

## Process model

`src/main/resources/bpmn/order-fulfillment.bpmn`: Order received, Validate order (async service task, one try), gateway
"Large order?" (amount above 1000 goes to the user task Approve order), Ship order, Order shipped. Open it in bpmn.io or Cockpit.

## Prerequisites

JDK 21, Docker (Compose).

## Run it

```bash
chmod +x ../../../mvnw
docker compose up -d --wait        # PostgreSQL :5432, Prometheus :9090, Grafana :3000
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
# Metrics:            http://localhost:8080/actuator/prometheus
# Prometheus:         http://localhost:9090      Grafana: http://localhost:3000 (admin / admin), dashboard "Operaton"
```

Throwaway credentials: PostgreSQL `operaton`/`operaton`, Grafana `admin`/`admin`. Prometheus reaches the application on the
host through `host.docker.internal:8080` (set via `extra_hosts`, works on Docker Desktop, Colima and Linux).

## Walkthrough

An `OrderSimulator` (switch off with `example.simulator.enabled=false`) starts an order every two seconds: every third is large
and waits for approval (the simulator approves after a few seconds), every seventh has an unknown article and ends as a
`failedJob` incident. Watch the Grafana panels: started/ended per minute, running instances, job backlog, open incidents.
Resolve it in Cockpit (Incidents, retry after fixing the article variable) or cancel the instance: `operaton_incidents_open`
drops and `operaton_incidents_resolved_total` / `operaton_incidents_deleted_total` rise.

## How it works

- `application.yaml`: exposes the `prometheus` endpoint, history level `full` (needed for incident counters) and the plugin properties
  (`running-instances-enabled` is switched on for the instances gauge).
- `ValidateOrderDelegate` throws for an unknown article; `OrderSimulator` produces the load.
- `compose/prometheus.yml` and `compose/grafana/` hold the scrape config, data source and the dashboard JSON.

## Run the tests

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL), Docker required
```

`OrderMetricsIT` runs happy and error orders end to end and asserts the metric values on `/actuator/prometheus`.

# Identity SCIM Plugin - example

An expense approval process whose people live in a SCIM directory: the user task "Approve expense" is
assigned to the SCIM group `approvers`, "Book payout" to `finance`, and the SCIM group `operaton-admin`
administers the engine.

## What it shows

* The Spring Boot starter configuring the plugin (`application.yaml`): bearer token, 30 s cache, password check at a token endpoint.
* The engine's identity service resolving users, groups and candidate groups from SCIM. WireMock stands in for the SCIM server of Entra ID, Okta or Keycloak with the users `alice` and `carol` (approvers), `bob` (finance) and `demo` (administrator); the mappings are in [`compose/wiremock/mappings/scim.json`](compose/wiremock/mappings/scim.json).
* Authorizations granted to SCIM group ids ([`ExpenseAuthorizations`](src/main/java/org/operaton/extensions/plugin/identityscim/example/ExpenseAuthorizations.java)) applying to whoever the directory lists as members.
* An unavailable directory failing the lookup loudly.

Deviation from the example conventions: there is no `operaton.bpm.admin-user`, because the identity
service is read-only; `demo` (password `demo`) is a member of the SCIM group `operaton-admin`.

Process model: [`expense-approval.bpmn`](src/main/resources/bpmn/expense-approval.bpmn) (PNG not
rendered yet; open it in Cockpit or bpmn.io): expense submitted, approve (group `approvers`), then
pay out (group `finance`) or end rejected.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL on 5432 (operaton/operaton), SCIM stub on 8089
../../../mvnw spring-boot:run # run `chmod +x ../../../mvnw` first if needed
# Cockpit / Tasklist: http://localhost:8080, login alice/alice, bob/bob, carol/carol or demo/demo
```

The password of every stub user is its user name (throwaway). The stub answers only the queries of
this scenario; other requests (for example Cockpit's user search) return an empty list or `401`.

## Walkthrough

* Log in as `alice`, start `expense-approval`, claim and complete "Approve expense" with `approved=true`.
* Log in as `bob`: "Book payout" is a candidate task of the group `finance`; complete it.
* Complete with `approved=false`: the process ends at "Expense rejected" and `bob` sees nothing.
* `bob` never sees "Approve expense" as a candidate, `demo` sees every task.
* Edit the members in `scim.json`, restart the stub (`docker compose restart scim`) and wait out the cache (30 s): the task candidates change without touching the engine.

## How it works

* Plugin settings: [`application.yaml`](src/main/resources/application.yaml)
* Group authorizations and the administrator group: [`ExpenseAuthorizations`](src/main/java/org/operaton/extensions/plugin/identityscim/example/ExpenseAuthorizations.java)
* The SCIM directory: [`scim.json`](compose/wiremock/mappings/scim.json)

## Run the tests

```bash
../../../mvnw verify          # Testcontainers (PostgreSQL + WireMock), Docker required
```

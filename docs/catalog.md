# Extension catalog

Is there an extension for …? This is the list of plugins and connectors for the
[Operaton](https://operaton.org) BPMN engine in this repository. Each entry links to the
extension's README, which has installation, configuration and the verified Operaton versions.

- **Plugins** extend the engine itself (parse listeners, interceptors, history, metrics, …).
- **Connectors** integrate external systems for use from BPMN.
- **Clients** are external task clients for other languages (JavaScript/TypeScript, Python, Go, Rust).

Status is `incubating`, `stable` or `deprecated`. "Operaton" is the supported minor range
(`compatibility` in `extension.yml`). Rules for entries: [documentation.md](conventions/documentation.md#catalog-entry-rule).

## Plugins

<!-- catalog:plugins:begin -->
| Extension | What it does | Operaton | Status |
|---|---|---|---|
| [History Kafka Plugin](../plugins/history-kafka/README.md) | Publishes Operaton history events to Apache Kafka after the engine transaction commits. | 2.0, 2.1, 2.2 | incubating |
| [History OpenSearch Plugin](../plugins/history-opensearch/README.md) | Indexes Operaton history as current-state documents into OpenSearch after the engine transaction commits. | 2.0, 2.1, 2.2 | incubating |
| [Incident Notifier Plugin](../plugins/incident-notifier/README.md) | Notifies operators about Operaton incidents by mail, webhook, Slack and Microsoft Teams with throttling. | 2.0, 2.1, 2.2 | incubating |
| [Micrometer Metrics Plugin](../plugins/micrometer-metrics/README.md) | Exposes Operaton engine metrics, job backlog, incidents and process instance counters through Micrometer. | 2.0, 2.1, 2.2 | incubating |
| [OpenTelemetry Tracing](../plugins/opentelemetry-tracing/README.md) | Creates OpenTelemetry spans for process instances, activities, jobs and external tasks and propagates the trace context through the process instance. | 2.0, 2.1, 2.2 | incubating |
| [Process Test Coverage](../plugins/process-test-coverage/README.md) | Measures which BPMN flow nodes and sequence flows your process tests execute, asserts a minimum coverage and writes an HTML and JSON report. | 2.0, 2.1, 2.2 | incubating |
<!-- catalog:plugins:end -->

## Connectors

<!-- catalog:connectors:begin -->
| Extension | What it does | Operaton | Status |
|---|---|---|---|
| [AI LLM connector](../connectors/ai-llm/README.md) | Calls large language models from BPMN processes - prompts, schema-validated structured extraction and a bounded tool-calling agent, built on LangChain4j. | 2.0, 2.1, 2.2 | incubating |
| [JDBC connector](../connectors/jdbc/README.md) | Run parameterised SQL queries and updates against relational databases from BPMN processes. | 2.0, 2.1, 2.2 | incubating |
| [Kafka connector](../connectors/kafka/README.md) | Produce to and consume from Apache Kafka in Operaton processes: send records after the engine transaction commits, start processes and correlate messages from records. | 2.0, 2.1, 2.2 | incubating |
| [Mail connector](../connectors/mail/README.md) | Sends, polls and deletes mail over SMTP/IMAP from BPMN, and starts or correlates processes from incoming mail. | 2.0, 2.1, 2.2 | incubating |
| [S3 Connector](../connectors/s3/README.md) | Store, fetch, list, copy, delete and presign objects on S3-compatible storage from BPMN processes. | 2.0, 2.1, 2.2 | incubating |
<!-- catalog:connectors:end -->

## Clients

<!-- catalog:clients:begin -->
| Language | Package | What it does | Operaton | Status |
|---|---|---|---|---|
| [TypeScript / JavaScript](../clients/js/README.md) | `@kthoms/operaton-external-task-client` (npm) | External task client for Operaton in TypeScript for Node.js 22+ with long polling, typed variables and graceful shutdown. | 2.0, 2.1, 2.2 | incubating |
| [Python](../clients/python/README.md) | `operaton-external-task-client` (PyPI) | External task client for Operaton in Python (PyPI operaton-external-task-client) with sync and asyncio workers, typed variables, filters, retries, BPMN errors and back-off. | 2.0, 2.1, 2.2 | incubating |
| [Go](../clients/go/README.md) | `github.com/kthoms/operaton-extensions/clients/go` | External task client for Operaton in Go - long-polling fetch-and-lock, typed variables, retries and BPMN errors, graceful shutdown, standard library only. | 2.0, 2.1, 2.2 | incubating |
| [Rust](../clients/rust/README.md) | `operaton-external-task-client` (crates.io) | Async external task client for Operaton in Rust (crates.io operaton-external-task-client) on tokio and reqwest, with typed variables, filters, retries, BPMN errors, back-off and graceful shutdown. | 2.0, 2.1, 2.2 | incubating |
<!-- catalog:clients:end -->

The tables are generated from the `extension.yml` manifests: run `tools/generate-catalog.py` after adding or changing an extension (`--check` verifies it).

## Proposed / backlog


Ideas waiting for an owner. Each line says where the idea comes from and whether code can be
reused (see [licensing-and-attribution.md](conventions/licensing-and-attribution.md)). An agent or
contributor picks one, opens an issue, and moves it to the tables above with the PR.

### Next candidates

| Kind | Extension | Source / reuse |
|---|---|---|
| connector | `amqp` (RabbitMQ) | fresh |
| connector | `chat` (Slack, Teams, Mattermost) | fresh |
| plugin | `identity-scim` (SCIM 2.0 for Entra ID, Okta) | cibseven `engine-plugins/identity-scim` (Apache-2.0): port |
| plugin | `custom-batch` | camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0, archived): port |
| connector | `a2a` (Agent2Agent protocol) | idea from operaton/operaton#3480: fresh |
| connector | `camel` | camunda-platform-7-camel (Apache-2.0): port |
| plugin | `graphql-api` | camunda-platform-7-graphql (Apache-2.0): port |
| plugin | `dmn-xlsx` (DMN from spreadsheets) | camunda-dmn-xlsx (Apache-2.0): port |
| plugin | `scenario-testing` | camunda-platform-scenario (Apache-2.0): port |
| plugin | `mockito-helpers` | holunda-io c7-mockito (Apache-2.0): port |
| plugin | `typed-variables` | idea from camunda-bpm-data (Apache-2.0) |
| plugin | `correlation-inbox` (message inbox/outbox) | idea from holunda camunda-bpm-correlate |
| plugin | `history-archive-s3` | fresh |
| plugin | `variable-encryption` | fresh |
| connector | `sftp`, `redis`, `mongodb`, `jms`, `aws-sqs-sns`, `aws-lambda` | fresh |
| connector | `msgraph` (Outlook, SharePoint, Teams), `google-workspace` (Sheets, Drive) | fresh |
| connector | `pdf` (templated documents), `webhook-inbound`, `cloudevents` | fresh |
| connector | `vector-store` (knowledge ingestion) | cibseven `KnowledgeIngestor` (Apache-2.0) |
| plugin | `grpc-external-tasks` | camunda-platform-7-grpc-external-task (Apache-2.0): port |

Not planned: Keycloak identity (exists as operaton/operaton-keycloak), REST/SOAP connectors
(in Operaton core), anything whose source license forbids reuse is only reimplemented from the
idea (n8n nodes, Camunda 8 connectors).

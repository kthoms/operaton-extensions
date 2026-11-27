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

| Extension | What it does | Operaton | Status |
|---|---|---|---|
| [history-kafka](../plugins/history-kafka/README.md) | Publishes Operaton history events to Apache Kafka after the engine transaction commits (JSON or CloudEvents) | 2.0, 2.1, 2.2 | incubating |

## Connectors

| Extension | What it does | Operaton | Status |
|---|---|---|---|
| | | | |

## Clients

| Language | Package | What it does | Operaton | Status |
|---|---|---|---|---|
| | | | | |

Entries are added with each extension, in the pull request that introduces it.

## Proposed / backlog


Ideas waiting for an owner. Each line says where the idea comes from and whether code can be
reused (see [licensing-and-attribution.md](conventions/licensing-and-attribution.md)). An agent or
contributor picks one, opens an issue, and moves it to the tables above with the PR.

### In progress (first wave)

| Kind | Extension | What it does | Source / reuse |
|---|---|---|---|
| plugin | `process-test-coverage` | Coverage of BPMN flow nodes and sequence flows by tests, HTML report, coverage assertions | camunda-community-hub/camunda-process-test-coverage (Apache-2.0, archived): port with attribution |
| plugin | `micrometer-metrics` | Micrometer metrics: engine metrics, job backlog, incidents, instance counters | idea from camunda-prometheus-process-engine-plugin (MIT, archived): fresh |
| plugin | `opentelemetry-tracing` | OpenTelemetry spans for activities, jobs and external tasks | fresh |
| plugin | `incident-notifier` | Mail / webhook (Slack, Teams) notifications on incidents, with throttling | camunda-community-hub/email-incident-notification-plugin (Apache-2.0): port and generalise |
| connector | `mail` | Send, poll, delete mail over SMTP/IMAP; polling can start processes | camunda-community-hub/camunda-platform-7-mail (Apache-2.0): port with attribution |
| connector | `kafka` | Produce after commit; consume to start and correlate | fresh (Camunda 8 connectors are not reusable) |
| connector | `jdbc` | Parameterised SQL query/update mapped to variables | fresh |
| connector | `ai-llm` | LLM prompts with structured output, optional agent/tool loop (LangChain4j) | cibseven `connect/ai-agent` (Apache-2.0): port the core |
| connector | `s3` | Put, get, list, delete, presign on S3-compatible storage | fresh |
| client | `js` | TypeScript external task client | camunda/camunda-external-task-client-js (Apache-2.0): port with attribution |
| client | `python` | Python external task client | camunda-community-hub/camunda-external-task-client-python3 (Apache-2.0): port with attribution |
| client | `go` | Go external task client | citilinkru/camunda-client-go (MIT): port, keep the MIT notice |
| client | `rust` | Rust external task client | fresh |

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
| plugin | `history-opensearch` | fresh |
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

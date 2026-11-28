# Incident Notifier Plugin

Notifies operators about Operaton incidents by mail, generic webhook, Slack and Microsoft Teams, with per-process-definition throttling.

## When to use

Use it when nobody watches Cockpit but incidents (failed jobs, failed external tasks) must reach a person or a channel quickly: a mail to the team inbox, a Slack or Teams message, or a JSON POST into your alerting system.

- Incidents are announced only after their transaction committed; a failing notification never affects the engine, it is logged with context and counted.
- A throttle window per process definition turns an incident storm into one digest.
- Not a monitoring system: there is no acknowledgement, retry queue or persistence. Pending digests are lost if the JVM dies. For metrics use a Micrometer/Prometheus plugin and alert on the incident count.

### Concepts

| Term | Meaning |
|---|---|
| Observed types | Incident types whose handler is decorated (default `failedJob`, `failedExternalTask`). The original handler keeps creating and resolving incidents. |
| Digest | The incidents of one process definition collected in one throttle window. |
| Throttle | Leading plus trailing edge: the first incident of a quiet period is sent at once, those arriving in the next `throttleMillis` are sent as one digest when the window ends. `0` sends every incident on its own. |
| Channel | Destination: `email`, `webhook`, `slack`, `teams`, or your own `NotificationChannel`. |

## Installation

Plain engine:

```xml
<dependency>
  <groupId>org.operaton.plugin.incident-notifier</groupId>
  <artifactId>incident-notifier-plugin</artifactId>
  <version>VERSION</version>
</dependency>
```

Register the plugin (properties are strings in `bpm-platform.xml`):

```xml
<plugin>
  <class>org.operaton.extensions.plugin.incidentnotifier.IncidentNotifierPlugin</class>
  <properties>
    <property name="mailHost">smtp.example.org</property>
    <property name="mailFrom">operaton@example.org</property>
    <property name="mailTo">ops@example.org</property>
    <property name="throttleMillis">60000</property>
  </properties>
</plugin>
```

Or in Java: `configuration.getProcessEnginePlugins().add(plugin)`; call `plugin.close()` after the engine is closed so buffered digests are flushed and the threads stop.

Spring Boot (the Operaton starter picks up the plugin bean):

```xml
<dependency>
  <groupId>org.operaton.plugin.incident-notifier</groupId>
  <artifactId>incident-notifier-plugin-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

Shared engine / distribution: put the plugin jar and `angus-mail` (with `jakarta.mail-api`, `angus-activation`) into the engine's `lib/` and add the `<plugin>` entry above to `bpm-platform.xml`.

## Usage

Configure at least one channel; an engine with no channel, or an invalid value, refuses to start.

```yaml
operaton:
  extensions:
    plugin:
      incident-notifier:
        throttle: PT1M
        cockpit-base-url: https://operaton.example.org/operaton
        email: { host: smtp.example.org, from: operaton@example.org, to: ops@example.org }
        slack: { url: https://hooks.slack.com/services/... }
```

Per process instance the mail recipients can be set with the variables `incidentNotificationReceiver` and `incidentNotificationCc` (names configurable); otherwise `to` is used. Incidents of one digest with different recipients are mailed separately.

### Templates

Placeholders start with `@`; substituted values are never re-interpreted; an unknown placeholder fails the start.

| Template | Placeholders |
|---|---|
| `subject` | `@COUNT`, `@PROCESS_DEFINITION_KEY`, `@PROCESS_DEFINITION_NAME` |
| `bodyTemplate` | the subject ones and `@INCIDENTS` |
| `incidentTemplate` | `@INCIDENT_ID`, `@INCIDENT_TYPE`, `@MESSAGE`, `@ACTIVITY`, `@PROCESS_DEFINITION_ID`, `@PROCESS_DEFINITION_KEY`, `@PROCESS_DEFINITION_NAME`, `@PROCESS_INSTANCE_ID`, `@BUSINESS_KEY`, `@TENANT_ID`, `@TIME`, `@URL` |

`@URL` is `<cockpitBaseUrl>/app/cockpit/default/#/process-instance/<id>` (empty without a base URL).

### Payloads

- Generic webhook: JSON `{"event":"operaton.incident.digest","processDefinitionKey","count","suppressed","subject","text","incidents":[{id,type,message,activityId,processDefinitionId,processDefinitionKey,processDefinitionName,processInstanceId,executionId,businessKey,tenantId,timestamp,cockpitUrl}]}`.
- Slack: `{"text": "*subject*\n..."}` (mrkdwn, `& < >` escaped).
- Teams: `{"type":"message","attachments":[Adaptive Card]}` as accepted by Teams Workflows webhooks.

### Statistics and custom channels

`plugin.getStatistics()` returns counters (observed, collection failures, dropped, per channel digests/incidents sent and failed). `plugin.addChannel(NotificationChannel)` adds your own destination before the engine is built.

## Configuration

Plugin property (Java / `bpm-platform.xml`) and Spring Boot property (prefix `operaton.extensions.plugin.incident-notifier.`):

| Plugin property | Spring Boot property | Default | Meaning |
|---|---|---|---|
| | `enabled` | `true` | Register the plugin. |
| `incidentTypes` | `incident-types` | `failedJob,failedExternalTask` | Incident types to observe. |
| `throttleMillis` | `throttle` (Duration) | 60 s | Window per process definition, `0` = no throttling. |
| `cockpitBaseUrl` | `cockpit-base-url` | empty | Base URL of the web apps, for links. |
| `maxIncidentsPerMessage` | `max-incidents-per-message` | `20` | Incidents rendered per message, rest summarised. |
| `maxBufferedIncidents` | `max-buffered-incidents` | `500` | Buffer per process definition and window; oldest dropped (counted). |
| `senderThreads` | `sender-threads` | `2` | Delivery threads (queue of 1000 digests, then dropped and counted). |
| `timeZone` | `time-zone` | system | Zone for rendered times. |
| `recipientVariable` / `ccVariable` | `recipient-variable` / `cc-variable` | `incidentNotificationReceiver` / `incidentNotificationCc` | Process variables with mail recipients. |
| `subject` | `template.subject` | `[Operaton] @COUNT incident(s) in process @PROCESS_DEFINITION_KEY` | Subject / title. |
| `bodyTemplate` | `template.body` | see source | Body template. |
| `incidentTemplate` | `template.incident` | see source | One incident. |
| `mailHost` | `email.host` | none | SMTP host; enables the email channel. |
| `mailPort` | `email.port` | `587` | SMTP port. |
| `mailUsername` / `mailPassword` | `email.username` / `email.password` | none | SMTP auth (only when a username is set). |
| `mailFrom` | `email.from` | none | Sender (required with a host). |
| `mailTo` / `mailCc` | `email.to` / `email.cc` | none / none | Default recipients (`to` required with a host). |
| `mailStartTls` | `email.starttls` | `true` | STARTTLS when offered. |
| `mailStartTlsRequired` | `email.starttls-required` | `false` | Fail when STARTTLS is not offered. |
| `mailSsl` | `email.ssl` | `false` | Implicit TLS (SMTPS). |
| `mailTimeoutMillis` | `email.timeout` | 10 s | Connect/read/write timeout. |
| `webhookUrl` | `webhook.url` | none | Generic webhook; enables the channel. |
| `webhookHeaders` (Map) | `webhook.headers.<name>` | none | Extra headers, e.g. `Authorization` (Java/Spring only). |
| `webhookTimeoutMillis` | `webhook.timeout` | 10 s | Timeout of all webhook channels (Spring Boot: `webhook.timeout`). |
| `slackWebhookUrl` | `slack.url` | none | Slack incoming webhook. |
| `teamsWebhookUrl` | `teams.url` | none | Teams webhook. |

Secrets (SMTP password, webhook URLs and headers) are never logged; failures log the scheme, host and port only.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): a payment process whose "Charge payment" service task fails for large amounts; the resulting incident is mailed to a Mailpit inbox and posted to a webhook. `docker compose up -d --wait`, `./mvnw spring-boot:run`, `./mvnw verify` (see its README).

## Development

```bash
./mvnw -B -pl plugins/incident-notifier -amd verify
./mvnw -B -pl plugins/incident-notifier/incident-notifier-plugin -am verify -Doperaton.version=2.1.6
```

Tests: unit tests (renderer, JSON, dispatcher, configuration validation), embedded-engine tests with `ProcessEngineExtension` and H2 (failed job and external task incidents, rollback, failing channel), GreenMail for SMTP, a JDK `HttpServer` for the webhooks; the example runs the full scenario with PostgreSQL and Mailpit via Testcontainers.

### Design notes and decisions

- The handlers are decorated in `postInit` (after the engine built its handler map), so default, composite and user-registered handlers keep their behaviour.
- Notification data is collected inside the command (no engine access later) and handed over in a `COMMITTED` transaction listener. Collecting is the only place that catches and counts exceptions inside the engine; delivery runs on daemon threads.
- Throttle key is the process definition key (all versions together). Windows, buffers and the sender queue are bounded; overflow is counted as dropped and mentioned in the digest.
- The engine has no shutdown hook for plugins: close the plugin (the starter registers it as a bean `close()`), otherwise the daemon threads end with the JVM and buffered digests are lost.
- JSON is written by a small internal writer so the artifact has no JSON library dependency (the starter works with Jackson 2 and 3 alike).
- Guesses: Teams uses the Adaptive Card message format of Workflows webhooks (the retired Office 365 connector cards are not produced); mail is plain text; default STARTTLS is opportunistic, set `mailStartTlsRequired` to enforce it; webhook headers can only be set from Java/Spring, not from `bpm-platform.xml`.
- Known limitations: no retry of failed deliveries, no persistence of pending digests, one plugin instance per engine, the starter is built against the parent's Spring Boot version and only the artifact module is verified on each Operaton minor.

### Sources and attribution

Port and generalisation of [camunda-community-hub/email-incident-notification-plugin](https://github.com/camunda-community-hub/email-incident-notification-plugin) (Apache-2.0, Copyright 2020 J-IT IT-Dienstleistungs GesmbH): the idea of decorating the incident handlers, the placeholder syntax, per-instance recipient variables and the mail grouping. Ported files keep the original header with a "Ported from" line; see [NOTICE](NOTICE). New compared to the source: asynchronous delivery after commit, per-definition throttling, webhook/Slack/Teams channels, Spring Boot starter, Operaton/Jakarta APIs.

# Mail connector

Sends, polls and deletes mail over SMTP/IMAP from BPMN, and starts or correlates processes from incoming mail.

## When to use

Use it when a process has to send a mail (notification, reply, document), read a mailbox, or when an incoming
mail should start a process or continue a waiting one (support desk, order by mail, invoice inbox).

It plugs into Operaton Connect, so it is used like any connector: `<operaton:connectorId>mail-send</operaton:connectorId>`
with input and output parameters. Three connectors and one inbound poller:

| Part | What it does |
|---|---|
| `mail-send` | Sends a mail: to/cc/bcc, text and/or HTML, templates, attachments from files, byte arrays and file variables |
| `mail-poll` | Reads messages of an IMAP folder with filters; returns them as a variable |
| `mail-delete` | Deletes messages from an IMAP folder |
| inbound poller | Polls a folder and starts a process or correlates a message per configurable route |

Not for: high-volume mass mailing (one connection per send), mailboxes that need OAuth2 (only user/password;
Jakarta Mail OAuth2 properties can be passed through `properties`), IMAP IDLE push (the poller polls).

## Installation

Plain engine or any application that registers engine plugins:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-mail</artifactId>
  <version>VERSION</version>
</dependency>
```

The engine needs the Operaton Connect plugin (`ConnectProcessEnginePlugin`, artifact `operaton-engine-plugin-connect`;
the Spring Boot starter of Operaton includes it). Register the mail plugin with the engine configuration:

```java
MailConnectorPlugin mail = new MailConnectorPlugin();
mail.setConfigLocation("classpath:mail-config.properties"); // or mail.setSettings(MailSettings.builder()...build())
configuration.getProcessEnginePlugins().add(mail);
```

Without the plugin the connectors read the default file `mail-config.properties` (classpath), or the file named by
`-Doperaton.extensions.connector.mail.config=...` / environment variable `OPERATON_MAIL_CONFIG`.

Spring Boot (adds auto-configuration and the inbound poller):

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-mail-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

Shared engine / distribution: put `operaton-connector-mail`, `jakarta.mail-api` and `angus-mail` into the engine's
`lib/` and register `org.operaton.extensions.connector.mail.MailConnectorPlugin` in `bpm-platform.xml`
(plugin property `configLocation`).

## Usage

### Sending

```xml
<bpmn:serviceTask id="SendReply" name="Send reply mail">
  <bpmn:extensionElements>
    <operaton:connector>
      <operaton:connectorId>mail-send</operaton:connectorId>
      <operaton:inputOutput>
        <operaton:inputParameter name="to">${customerMail}</operaton:inputParameter>
        <operaton:inputParameter name="subjectTemplate">Invoice {{number}}</operaton:inputParameter>
        <operaton:inputParameter name="textTemplate">Dear {{customer}}, your invoice {{number}} is attached.</operaton:inputParameter>
        <operaton:inputParameter name="templateVariables">
          <operaton:map>
            <operaton:entry key="customer">${customer}</operaton:entry>
            <operaton:entry key="number">${number}</operaton:entry>
          </operaton:map>
        </operaton:inputParameter>
        <operaton:inputParameter name="attachmentVariables">
          <operaton:list><operaton:value>invoice</operaton:value></operaton:list>
        </operaton:inputParameter>
      </operaton:inputOutput>
    </operaton:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

| Input parameter | Meaning |
|---|---|
| `to` (required), `cc`, `bcc`, `replyTo` | String (comma-separated) or list of strings |
| `from`, `fromAlias` | Sender; default `mail.sender` / `mail.sender.alias` |
| `subject`, `text`, `html` | Literal content. `text` and `html` together give a multipart/alternative mail |
| `subjectTemplate`, `textTemplate`, `htmlTemplate`, `templateVariables`, `templateEngine` | Rendered content (see below); a literal and its template are mutually exclusive |
| `inReplyTo` | Message-ID of the mail being answered (sets `In-Reply-To` and `References`) |
| `fileNames` | List of file paths to attach |
| `files` | Map file name to `byte[]` or `InputStream` |
| `attachments` | List of file values (for example `${execution.getVariableTyped('invoice')}`) |
| `attachmentVariables` | Names of process variables of type file; the file name and MIME type of the variable are used |

Templates: `templateEngine` is `simple` (default) or `freemarker`. The simple engine replaces `{{name}}`, `${name}`
and `{{a.b}}` (nested maps) and fails on a placeholder without a value; values are HTML-escaped in `htmlTemplate`.
Inside a BPMN model the engine evaluates `${...}` itself before the connector sees it, so use `{{name}}` in models
(or pass the template as a variable). FreeMarker needs `org.freemarker:freemarker` on the classpath (optional
dependency of this artifact; Operaton's own FreeMarker template engine brings it); HTML templates are auto-escaped
and `?new` / `?api` are disabled.

`mail-send` has no output. A failure (invalid address, missing attachment, server unreachable) throws
`MailConnectorException` and fails the service task like any other exception.

### Polling and deleting

```xml
<operaton:connector>
  <operaton:connectorId>mail-poll</operaton:connectorId>
  <operaton:inputOutput>
    <operaton:inputParameter name="subject">Invoice</operaton:inputParameter>
    <operaton:inputParameter name="unreadOnly">true</operaton:inputParameter>
    <operaton:inputParameter name="limit">10</operaton:inputParameter>
    <operaton:outputParameter name="mails">${mails}</operaton:outputParameter>
    <operaton:outputParameter name="mailCount">${count}</operaton:outputParameter>
  </operaton:inputOutput>
</operaton:connector>
```

`mail-poll` inputs: `folder`, `unreadOnly`, `from` (contains), `subject` (contains), `receivedSince` (`java.util.Date`,
day granularity), `limit` (oldest first), `markAsRead` (default false: reading does not change the mailbox),
`download-attachments`. Outputs: `mails` (list of `Mail`: `from`, `fromAddress`, `to`, `cc`, `subject`, `text`, `html`,
`sentDate`, `receivedDate`, `messageId`, `attachments` with `fileName`, `contentType`, `size`, `path`) and `count`.
Attachment names are sanitised (no directories) and written to `mail.attachment.path` in a unique sub-folder per mail.

`mail-delete` inputs: `folder` and exactly one of `mails` (the objects from `mail-poll`), `messageIds`, `messageNumbers`.
Messages are flagged and expunged; an unknown id fails the task.

`Mail` objects are Java-serializable process variables. If your engine disallows Java serialization for variables,
map what you need (`${mails[0].subject}`) instead of storing the list.

### Inbound: mail starts or continues a process

The inbound poller (`MailInboundPoller`) polls a folder for unread mails and routes each to a process. A `MailRoute`
has optional patterns (regular expressions, `find()`) for the sender address and the subject and says what to do;
the first matching route wins:

1. a business key was extracted (group 1 of `businessKeyPattern` on the subject) and `messageName` is set: the
   message is correlated to the process instance with that business key;
2. `processDefinitionKey` is set: a process instance is started (with the business key, if any);
3. otherwise the message is correlated to a message start event.

So `process-definition-key` + `message-name` + `business-key-pattern` gives "new mail starts a ticket, a reply with the
ticket id in the subject continues it".

Variables set from the mail (prefix `mail`, configurable): `mailFrom`, `mailFromAddress`, `mailTo`, `mailCc`,
`mailSubject`, `mailText`, `mailHtml`, `mailMessageId`, `mailReceivedDate`, `mailAttachmentCount` and the file
variables `mailAttachment1`, `mailAttachment2`, ...

Behaviour: delivery is at-least-once (a mail is completed only after the process was started; a crash in between
leads to a duplicate, `mailMessageId` lets the process detect it). After success a mail is marked read (default),
deleted or moved (`completion`). A mail without a matching route is left untouched. A mail whose processing fails
(for example a correlation without a target) is logged at ERROR, flagged (`\Flagged`) and not retried; remove the flag
to retry. Without Spring Boot, create and start the poller yourself and close it on shutdown:

```java
InboundSettings inbound = InboundSettings.builder()
    .route(MailRoute.builder("tickets").subjectPattern("(?i)support").businessKeyPattern("\\[(T-\\d+)]")
        .processDefinitionKey("support-ticket").messageName("ticket-reply").build())
    .build();
MailInboundPoller poller = new MailInboundPoller(mailPlugin.getMailService(), inbound, engine.getRuntimeService());
poller.start();   // poller.close() on shutdown
```

## Configuration

### Properties file (`mail-config.properties`) / Jakarta Mail properties

Every Jakarta Mail property (`mail.smtp.*`, `mail.imap.*`) is passed through. Typical file:

```properties
mail.smtp.host=smtp.example.org
mail.smtp.port=587
mail.smtp.starttls.enable=true
mail.smtp.auth=true
mail.smtp.user=robot
mail.smtp.password=...
mail.imap.host=imap.example.org
mail.imap.port=993
mail.imap.ssl.enable=true
mail.imap.user=support
mail.imap.password=...
mail.sender=support@example.org
```

Credentials are read from `mail.<smtp|imap>.user` / `.password`, falling back to `mail.user` / `mail.password`, and are
never logged (`MailSettings.toString()` masks them). Defaults added when absent: `mail.store.protocol=imap`,
`mail.transport.protocol=smtp`, `mail.imap.peek=true`, connection/read/write timeouts of 10/30/30 s.

| Property | Default | Meaning |
|---|---|---|
| `mail.sender` | none | Default sender address |
| `mail.sender.alias` | none | Default sender display name |
| `mail.poll.folder` | `INBOX` | Default folder of poll, delete and the inbound poller |
| `mail.attachment.download` | `true` | Download attachments of polled mails |
| `mail.attachment.path` | `attachments` | Download directory |

### Spring Boot (`operaton.extensions.connector.mail.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Switch the auto-configuration off |
| `config` | | Properties file to use instead of the typed properties below |
| `smtp.host`, `smtp.port` | | SMTP server |
| `smtp.tls` | `none` | `none`, `starttls` (required) or `ssl` |
| `smtp.username`, `smtp.password` | | Credentials (enable authentication) |
| `imap.host`, `imap.port`, `imap.tls`, `imap.username`, `imap.password` | | The same for IMAP |
| `sender`, `sender-alias` | | Defaults for `mail-send` |
| `folder` | `INBOX` | Default folder |
| `download-attachments`, `attachment-path` | `true`, `attachments` | Attachment handling of `mail-poll` |
| `properties.<jakarta.mail.property>` | | Any further Jakarta Mail property |
| `inbound.enabled` | `false` | Start the inbound poller |
| `inbound.folder` | `folder` | Folder to watch |
| `inbound.poll-interval` | `30s` | Time between polls |
| `inbound.completion` | `mark-seen` | `mark-seen`, `delete` or `move` |
| `inbound.processed-folder` | | Target of `move` (created when missing) |
| `inbound.variable-prefix` | `mail` | Prefix of the variables created from a mail |
| `inbound.batch-size` | `50` | Maximum mails handled per poll |
| `inbound.routes[n].name` | | Unique route name |
| `inbound.routes[n].from-pattern`, `.subject-pattern` | any | Regular expressions |
| `inbound.routes[n].business-key-pattern` | | Regular expression, group 1 of the subject is the business key |
| `inbound.routes[n].process-definition-key`, `.message-name` | | The action, at least one |

Invalid configuration (no host, bad pattern, duplicate route, `move` without folder) stops the application at startup.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.0 | yes |
| 2.1 | yes |
| 2.2 | yes |

## Example

[`example/`](example/): a support desk. A customer mail reaches the support mailbox (GreenMail), the inbound poller
starts the `support-ticket` process, an agent answers in Tasklist, and the reply is sent with `mail-send` to the
customer (caught by Mailpit). Run it:

```bash
cd connectors/mail/example
docker compose up -d --wait
../../../mvnw spring-boot:run        # Cockpit/Tasklist http://localhost:8080 (demo/demo, agents alice/alice, bob/bob)
../../../mvnw verify                 # end-to-end IT with Testcontainers
```

Details: [example/README.md](example/README.md).

## Development

```bash
./mvnw -B -pl connectors/mail -amd verify                                              # whole extension incl. example (Docker)
./mvnw -B -pl connectors/mail/operaton-connector-mail -am verify -Doperaton.version=2.1.6   # one Operaton version
```

Tests: GreenMail (in-JVM SMTP/IMAP) for the connectors, the poller and the starter; `ProcessEngineExtension` with H2 and
the Connect plugin for BPMN use; Testcontainers (PostgreSQL, GreenMail, Mailpit) in the example.

Design notes and best-guess decisions:

- Connect keeps one connector instance per JVM, so connectors hold no engine state. They find their `MailService` in the
  `MailConnectorPlugin` of the engine that executes them (also inside Spring Boot's composite plugin) and otherwise
  load the default properties file once. Several engines with different accounts therefore work.
- Every mail operation opens and closes its own connection: simple and thread-safe, at the cost of one login per call.
- `MailSettings` is immutable; there is no static mutable configuration (the original used static factories).
- Dropped from the original: JNDI mail sessions and the IMAP IDLE notification service. The new inbound poller (routes,
  start or correlate, completion policies) is not part of the original.
- Added to the original: poll filters, `markAsRead`, `limit`, multipart/alternative, template rendering, file-variable
  attachments, `inReplyTo`, strict parameter validation with specific errors, sanitised attachment names, expunging delete.
- Request parameters failing validation throw `MailConnectorException` (the original logged and threw a generic error).
- Limits: `receivedSince` has IMAP's day granularity; only user/password authentication is configured through typed
  properties; the poller is at-least-once.

### Sources and attribution

Ported from [camunda-community-hub/camunda-platform-7-mail](https://github.com/camunda-community-hub/camunda-platform-7-mail)
(Apache-2.0, license checked with `gh`): the connector, request, response and provider structure and the `Mail` and
`Attachment` DTOs, adapted from `org.camunda` to `org.operaton` and Jakarta Mail (Angus). Ported files keep their original
header and carry "Ported from ...". See [NOTICE](NOTICE).

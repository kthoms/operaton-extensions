# Mail connector — example

A support desk: a customer's mail starts a ticket process, an agent answers in Tasklist, and the customer gets the
reply by mail.

## What it shows

- the inbound poller turning a mail into a process instance (route in `application.yaml`, business key from `[T-123]` in
  the subject);
- the `mail-send` connector in a service task, with a template, `inReplyTo` and variables taken from the mail.

## Process model

Support mail received, Answer ticket (user task, group `agents`), Send reply mail (`mail-send`), Ticket answered.
See `src/main/resources/support-ticket.bpmn` (open it in bpmn.io or Cockpit).

## Run it

Prerequisites: JDK 21, Docker.

```bash
docker compose up -d --wait   # PostgreSQL, GreenMail (support mailbox), Mailpit (outgoing mail)
chmod +x ../../../mvnw
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080  (demo / demo; agents alice/alice and bob/bob)
```

Throwaway credentials: PostgreSQL `operaton`/`operaton`; support mailbox IMAP user `support`, password `secret`
(address `support@localhost`). Ports: 5432, GreenMail SMTP 3025 / IMAP 3143 / API 8081, Mailpit SMTP 1025 / UI 8025.

## Walkthrough

1. Send a mail to the support mailbox, for example with Mailpit-independent `swaks`:
   `swaks --server localhost:3025 --to support@localhost --from carol@customer.example --header "Subject: [T-100] Problem with my invoice" --body "The total is wrong."`
2. Within five seconds a process instance with business key `T-100` exists; the task "Answer ticket" is in Tasklist.
3. Log in as `alice`, claim the task, enter an answer, complete it.
4. Open Mailpit (http://localhost:8025): the reply "Re: [T-100] Problem with my invoice" to `carol@customer.example` is there.
5. Error path: a mail whose subject matches no route (for example "Buy now") is left unread and starts nothing.

## How it works

| What | Where |
|---|---|
| Mail servers, sender, route | `src/main/resources/application.yaml` (`operaton.extensions.connector.mail.*`) |
| Reply with `mail-send` | `SendReply` in `support-ticket.bpmn` |
| Agents alice and bob | `ExampleApplication` |

## Run the tests

```bash
../../../mvnw verify          # SupportTicketIT: PostgreSQL, GreenMail and Mailpit in Testcontainers, Docker required
```

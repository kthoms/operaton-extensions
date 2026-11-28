# AI LLM connector — example: support e-mail triage

Incoming support e-mails of an online shop are classified by a language model (category, urgency,
sentiment, one-line summary) with the `llm-extract` connector, and an exclusive gateway routes them
to the team lead (urgent), to billing or to first-level support.

## What it shows

- `llm-extract` with a JSON Schema: the answer is validated, an invalid answer is sent back to the
  model with the validation errors and retried, and an answer that never validates fails the job
  and raises an incident on the task.
- Prompt templates filled from process variables (`{{subject}}`, `{{body}}`).
- The classification stored as a JSON variable and used in gateway conditions
  (`${classification.urgency == 'high'}`).
- A named model configured in `application.yaml` (Ollama through its OpenAI-compatible API) and the
  token usage of the call as a process variable (`classificationTokens`).

## Process model

[`src/main/resources/support-email-triage.bpmn`](src/main/resources/support-email-triage.bpmn)
(open it in the Operaton Modeler, bpmn.io or Cockpit):

```
(Support e-mail received) -> [Classify e-mail] -> <Route by classification>
    urgent       -> [Answer escalated e-mail]  (leads) -> (Escalated e-mail answered)
    billing      -> [Answer billing question]  (billing)       -> (Billing question answered)
    anything else-> [Answer support e-mail]    (support)       -> (Support e-mail answered)
```

## Prerequisites

JDK 21, Docker (Docker Compose). About 1 GB of disk for the model.

## Run it

```bash
docker compose up -d --wait                          # PostgreSQL 5432 (operaton/operaton), Ollama 11434
docker compose exec ollama ollama pull qwen2.5:1.5b  # once: the small model the example uses
../../../mvnw spring-boot:run
```

Cockpit and Tasklist: <http://localhost:8080> — `demo`/`demo` (admin), `alice`/`alice` (support,
billing), `bob`/`bob` (team leads). These are throwaway credentials for a local run.

Start an instance through the REST API:

```bash
curl -s -X POST http://localhost:8080/engine-rest/process-definition/key/support-email-triage/start \
  -H 'Content-Type: application/json' \
  -d '{"variables": {
        "sender":  {"value": "jane@example.org"},
        "subject": {"value": "Order 1042"},
        "body":    {"value": "You charged me twice for order 1042! I want my money back today."}}}'
```

To use another model, override the properties, for example OpenAI:
`LLM_BASE_URL=https://api.openai.com/v1 LLM_MODEL=gpt-4o-mini LLM_API_KEY=sk-... ../../../mvnw spring-boot:run`.
A larger local model (`qwen2.5:7b`, `llama3.1:8b`) classifies more reliably than the 1.5B one.

## Walkthrough

- **Happy path**: the double-charge e-mail above is classified as `billing`, urgency `high`,
  sentiment `negative`, and lands in bob's group as "Answer escalated e-mail". Cockpit shows the
  `classification` variable as JSON. An invoice question goes to billing, a feature request to
  first-level support.
- **Error path**: when the model answers with something that is not valid against the schema three
  times in a row (first answer and two retries), the job fails. It is retried twice (`R2/PT2S`), then
  Cockpit shows an incident on "Classify e-mail" with the validation errors. Fix the model or prompt,
  then retry the job in Cockpit.

## How it works

| Model element | Where it is configured |
|---|---|
| Classify e-mail (service task, async) | `operaton:connector` with `llm-extract`: system prompt, user prompt template, JSON Schema, output mapping `classification = ${result}` in the BPMN |
| Model `triage` | `operaton.extensions.connector.ai-llm.models.triage.*` in [`application.yaml`](src/main/resources/application.yaml) |
| Route by classification | conditions on `classification.urgency` and `classification.category` |
| JSON variable | Spin JSON (`operaton-engine-plugin-spin`) and `operaton.bpm.default-serialization-format: application/json` |
| Users and groups | [`SeedUsers`](src/main/java/org/operaton/extensions/connector/aillm/example/SeedUsers.java) |

## Run the tests

```bash
../../../mvnw verify
```

[`SupportTriageIT`](src/test/java/org/operaton/extensions/connector/aillm/example/SupportTriageIT.java)
runs the process end to end on PostgreSQL (Testcontainers): escalation, billing, a prose answer that
is retried and then routed, and an answer that never validates and ends in an incident. The model is
a local fake of the chat completions API (`FakeChatCompletionsServer`) that classifies by keywords,
so the tests run offline and without a GPU; everything else is real.

# AI LLM connector

Calls large language models from BPMN processes: prompts, schema-validated structured extraction
and a bounded tool-calling agent, built on [LangChain4j](https://docs.langchain4j.dev).

Three Connect connectors and their JavaDelegate counterparts:

| Connector id | Delegate | What it does |
|---|---|---|
| `llm-prompt` | `LlmPromptDelegate` | Renders a system and a user prompt from process variables and returns the model's text answer. |
| `llm-extract` | `LlmExtractDelegate` | Asks for a JSON document, validates it against a JSON Schema and retries a bounded number of times with the validation errors; fails the task when it never validates. |
| `llm-agent` | `LlmAgentDelegate` | Lets the model call explicitly enabled tools (read allowed process variables, call registered Java bean methods) until it answers, at most `maxIterations` model calls. |

Models are configured by name (OpenAI and every OpenAI-compatible server such as Ollama, vLLM or
LM Studio, Azure OpenAI, Anthropic) and chosen per task. API keys come only from configuration.

## When to use

- A process step needs judgement on free text: classify an e-mail or ticket, extract fields from a
  letter, summarise a case for the person who works on it, draft an answer for review.
- You want the result as process data you can route on (`llm-extract` and an exclusive gateway),
  and you want a wrong answer to stop the process visibly (job retry, incident) instead of flowing
  on.
- You need a small agent loop whose reach you control: it can only call the tools you registered
  and enabled on the task, and it stops after a fixed number of model calls.

When not to use it:

- Deterministic rules belong in DMN or code; an LLM is slower, costs money per call and is not
  reproducible.
- Long conversations with chat memory, retrieval (RAG), MCP servers or agents that start processes:
  this connector keeps each task self-contained and stateless. CIB seven's `connect/ai-agent` goes
  much further in that direction.
- Streaming answers to a user interface: the connector waits for the complete answer.
- A model call holds the job and its database transaction for the call's duration. Make LLM tasks
  asynchronous (`operaton:asyncBefore="true"`) so a slow or failing model call becomes a retried
  job instead of a slow API call.

## Installation

### Spring Boot

```xml
<dependency>
  <groupId>org.operaton.connector.ai-llm</groupId>
  <artifactId>ai-llm-connector-spring-boot-starter</artifactId>
  <version><version></version>
</dependency>
<!-- Connect, which runs the llm-* connectors (not needed for the delegates) -->
<dependency>
  <groupId>org.operaton.bpm</groupId>
  <artifactId>operaton-engine-plugin-connect</artifactId>
</dependency>
<!-- Spin JSON: for JSON results stored as variables and for answers over 4000 characters -->
<dependency>
  <groupId>org.operaton.bpm</groupId>
  <artifactId>operaton-engine-plugin-spin</artifactId>
</dependency>
<dependency>
  <groupId>org.operaton.spin</groupId>
  <artifactId>operaton-spin-dataformat-json-jackson</artifactId>
</dependency>
```

The Operaton starter registers the Connect and Spin plugins when they are on the class path; this
starter registers `AiLlmPlugin` from the `operaton.extensions.connector.ai-llm.*` properties
(see [Configuration](#configuration)). With `operaton.bpm.default-serialization-format:
application/json`, map results (the `result` of `llm-extract`) are stored as JSON.

### Plain engine

Add `ai-llm-connector` and `operaton-engine-plugin-connect`, and register both plugins:

```xml
<dependency>
  <groupId>org.operaton.connector.ai-llm</groupId>
  <artifactId>ai-llm-connector</artifactId>
  <version><version></version>
</dependency>
```

```xml
<bean id="processEngineConfiguration" class="org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration">
  <!-- ... -->
  <property name="processEnginePlugins">
    <list>
      <bean class="org.operaton.connect.plugin.impl.ConnectProcessEnginePlugin"/>
      <bean class="org.operaton.extensions.connector.aillm.AiLlmPlugin">
        <property name="models">
          <map>
            <entry key="default">
              <bean class="org.operaton.extensions.connector.aillm.LlmModelConfig">
                <property name="provider" value="openai"/>
                <property name="baseUrl" value="http://localhost:11434/v1"/>
                <property name="model" value="qwen2.5:1.5b"/>
                <property name="timeoutSeconds" value="120"/>
              </bean>
            </entry>
          </map>
        </property>
      </bean>
    </list>
  </property>
</bean>
```

In Java: `new AiLlmPlugin().addModel("default", config).addTool(BeanMethodTool.of(...))`.

### Shared engine / distribution

Put `ai-llm-connector` and its dependencies (LangChain4j core, OpenAI and Anthropic
modules, Jackson 2, `json-schema-validator`; `mvn dependency:copy-dependencies` on the artifact
lists them) into the server's `lib/` folder and add the plugin to `bpm-platform.xml`:

```xml
<plugin>
  <class>org.operaton.extensions.connector.aillm.AiLlmPlugin</class>
  <properties>
    <property name="defaultModel">default</property>
  </properties>
</plugin>
```

Models are a map and cannot be set as `bpm-platform.xml` properties: subclass `AiLlmPlugin` and add
them in the constructor (reading keys from the environment), or use Spring or Spring Boot.

### Jackson

LangChain4j uses Jackson 2. Operaton's FEEL engine brings `jackson-module-scala`, which refuses to
load next to a newer `jackson-databind`. Keep all Jackson 2 modules on one version, for example by
importing `com.fasterxml.jackson:jackson-bom` (Spring Boot does this for you).

## Usage

### `llm-prompt`

```xml
<bpmn:serviceTask id="SummarizeTicket" name="Summarize ticket" operaton:asyncBefore="true">
  <bpmn:extensionElements>
    <operaton:connector>
      <operaton:connectorId>llm-prompt</operaton:connectorId>
      <operaton:inputOutput>
        <operaton:inputParameter name="systemPrompt">You summarize support tickets in two sentences.</operaton:inputParameter>
        <operaton:inputParameter name="userPrompt">Ticket from {{customer.name}}: {{ticketText}}</operaton:inputParameter>
        <operaton:outputParameter name="summary">${text}</operaton:outputParameter>
        <operaton:outputParameter name="summaryTokens">${totalTokens}</operaton:outputParameter>
      </operaton:inputOutput>
    </operaton:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

Prompts are templates: `{{name}}` is replaced by the process variable `name` (visible from the
task's execution), `{{customer.address.city}}` navigates into maps and `{{items.0}}` into lists.
Strings are inserted as they are; numbers, booleans, maps and lists as JSON. A placeholder that
cannot be resolved fails the task; `\{{` writes literal braces. The input `templateVariables` (a
map) replaces the process variables as the source. JUEL (`${...}`) in input parameters works as
usual and is evaluated before the template.

### `llm-extract`

```xml
<operaton:connector>
  <operaton:connectorId>llm-extract</operaton:connectorId>
  <operaton:inputOutput>
    <operaton:inputParameter name="userPrompt">Classify this e-mail: {{body}}</operaton:inputParameter>
    <operaton:inputParameter name="jsonSchema">{"type":"object","required":["category","urgency"],
      "properties":{"category":{"enum":["billing","technical","other"]},"urgency":{"enum":["low","high"]}}}</operaton:inputParameter>
    <operaton:outputParameter name="classification">${result}</operaton:outputParameter>
  </operaton:inputOutput>
</operaton:connector>
```

followed by a gateway with `${classification.urgency == 'high'}`. The JSON Schema (draft 2020-12
unless `$schema` says otherwise; references to remote schemas are refused) is put into the system
prompt, and for OpenAI-compatible models JSON mode is requested. The answer may be wrapped in a
Markdown code fence. When it is not valid JSON or does not validate, the model gets its answer back
with the validation errors and tries again, at most `retries` times (default 2); after that the
task fails with `LlmOutputValidationException`, so the engine retries the job and finally raises an
incident.

### `llm-agent`

```xml
<operaton:connector>
  <operaton:connectorId>llm-agent</operaton:connectorId>
  <operaton:inputOutput>
    <operaton:inputParameter name="systemPrompt">You answer questions about orders. Use the tools.</operaton:inputParameter>
    <operaton:inputParameter name="userPrompt">{{question}}</operaton:inputParameter>
    <operaton:inputParameter name="tools">order_status</operaton:inputParameter>
    <operaton:inputParameter name="readableVariables">orderId, customerName</operaton:inputParameter>
    <operaton:inputParameter name="maxIterations">5</operaton:inputParameter>
    <operaton:outputParameter name="answer">${text}</operaton:outputParameter>
  </operaton:inputOutput>
</operaton:connector>
```

Tools the model may call:

- `read_process_variable`: offered when `readableVariables` is set; reads only those variables.
- Registered tools named in `tools`. A registered tool is a public method of a bean
  (`operaton.extensions.connector.ai-llm.tools.<name>.bean/method/description`, or `BeanMethodTool`
  or your own `LlmTool` on the plugin). Parameters may be strings, numbers, booleans and enums;
  names come from `-parameters` or LangChain4j's `@P`.

A tool name the model invents and a `ToolRefusedException` (bad arguments, a value that is not
allowed) are answered to the model, which may correct itself. Any other exception from a tool fails
the task. Reaching `maxIterations` model calls without an answer fails the task with
`LlmAgentIterationLimitException`.

### Delegates

```xml
<bpmn:serviceTask id="ExtractAddress" operaton:class="org.operaton.extensions.connector.aillm.delegate.LlmExtractDelegate">
  <bpmn:extensionElements>
    <operaton:field name="userPrompt"><operaton:string>Extract the address from: {{letter}}</operaton:string></operaton:field>
    <operaton:field name="jsonSchema"><operaton:string>{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}</operaton:string></operaton:field>
    <operaton:field name="resultVariable"><operaton:string>address</operaton:string></operaton:field>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

The delegates take the same inputs as fields, require `resultVariable`, and also write
`<resultVariable>InputTokens`, `<resultVariable>OutputTokens` and `<resultVariable>TotalTokens`.
Use them with `operaton:class` (one instance per call), not as shared Spring beans.

### Inputs

| Input | Connectors | Type | Meaning |
|---|---|---|---|
| `model` | all | string | Configured model name; default `default-model`. |
| `systemPrompt` | all | string | System prompt template. |
| `userPrompt` | all | string | User prompt template. Required. |
| `templateVariables` | all | map | Values for the placeholders instead of the process variables. |
| `temperature` | all | number | Overrides the model's temperature. |
| `maxTokens` | all | integer | Overrides the model's `max-tokens`. |
| `resultVariable` | all | string | Also writes the result (`text` or `result`) to this process variable; text over 4000 characters is stored as a JSON object value. |
| `jsonSchema` | extract | string or map | The JSON Schema. Required. |
| `retries` | extract | integer | Retries after an invalid answer, 0 to 10; default `extract-retries`. |
| `resultFormat` | extract | `json` or `string` | For `resultVariable`: JSON object value (needs Spin JSON) or JSON text. Default `json`. |
| `tools` | agent | list or comma-separated string | Registered tools to enable. |
| `readableVariables` | agent | list or comma-separated string | Variables the `read_process_variable` tool may read. |
| `maxIterations` | agent | integer | Maximum model calls, 1 to 50; default `agent-max-iterations`. |

Any other input fails the task, and an input named like an API key (`apiKey`, `api-key`, ...) is
refused with an explanation: keys are configuration, never process data.

### Outputs

| Output | Connectors | Type | Meaning |
|---|---|---|---|
| `text` | prompt, agent | string | The answer. |
| `result` | extract | map, list or scalar | The validated document. |
| `resultJson` | extract | string | The validated document as JSON text. |
| `attempts` | extract | integer | Model calls it took. |
| `iterations`, `toolCalls` | agent | integer | Model calls and tool calls. |
| `modelName` | all | string | Model that answered, as reported by the provider. |
| `finishReason` | prompt | string | `STOP`, `LENGTH`, ... |
| `inputTokens`, `outputTokens`, `totalTokens` | all | long | Token usage summed over all model calls; `null` when a provider did not report it. |
| `modelCalls` | all | integer | Number of model calls. |

Every model call logs model, token usage and finish reason at DEBUG
(`org.operaton.extensions.connector.aillm`); prompts, answers and keys are never logged.

## Configuration

Spring Boot properties (prefix `operaton.extensions.connector.ai-llm`); the plugin has the same
settings as setters (`AiLlmPlugin`, `LlmModelConfig`).

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Register the plugin. |
| `default-model` | string | `default` | Model used when a task names none; must be configured. |
| `extract-retries` | int | `2` | Retries of `llm-extract`, 0 to 10. |
| `agent-max-iterations` | int | `10` | Maximum model calls of `llm-agent`, 1 to 50. |
| `models.<name>.provider` | string | (required) | `openai` (OpenAI and compatible servers), `azure-openai` or `anthropic`. |
| `models.<name>.base-url` | string | provider's | API base URL, e.g. `http://localhost:11434/v1` (Ollama), `http://localhost:8000/v1` (vLLM), `http://localhost:1234/v1` (LM Studio), `https://<resource>.openai.azure.com/openai/v1` (Azure, required). |
| `models.<name>.model` | string | (required) | Model name; deployment name for Azure. |
| `models.<name>.api-key` | string | none | API key, e.g. `${OPENAI_API_KEY}`. Optional for `openai`, required otherwise. Never logged. |
| `models.<name>.temperature` | double | provider's | 0.0 to 2.0. |
| `models.<name>.timeout` | duration | `60s` | Connect and read timeout per model call. |
| `models.<name>.max-tokens` | int | provider's | Maximum generated tokens per call (Anthropic: 1024 when unset). |
| `models.<name>.max-retries` | int | `2` | HTTP retries of a failed call (LangChain4j), 0 to 10. |
| `models.<name>.structured-output` | string | `json-object`; `prompt` for Anthropic | How `llm-extract` asks for JSON: `prompt` (schema in the prompt only), `json-object` (JSON mode), `json-schema` (schema as `response_format`, OpenAI-compatible only). |
| `tools.<name>.bean` | string | (required) | Spring bean that implements the tool. |
| `tools.<name>.method` | string | (required) | Public method of the bean; must not be overloaded. |
| `tools.<name>.description` | string | (required) | What the tool does; the model chooses tools by it. |

The configuration is validated when the engine starts; an invalid value stops the start with a
message naming the model and the property.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): incoming support e-mails are classified (category, urgency, sentiment) by
`llm-extract` and routed by an exclusive gateway to the team lead, billing or first-level support.
`docker compose up -d --wait` starts PostgreSQL and Ollama (pull the small model once:
`docker compose exec ollama ollama pull qwen2.5:1.5b`), `../../../mvnw spring-boot:run` starts the
application, `../../../mvnw verify` runs the end-to-end tests offline against a local fake of the
chat completions API. Details: [example/README.md](example/README.md).

## Development

```bash
./mvnw -B -pl connectors/ai-llm -amd verify                       # everything, incl. the example (Docker)
./mvnw -B -pl connectors/ai-llm/ai-llm-connector -am verify -Doperaton.version=2.1.6
```

Tests never call a real model: `LlmStubServer` (a JDK `HttpServer`) serves canned OpenAI chat
completions (text, tool calls, invalid JSON for the retry path, errors) and Anthropic messages, and
records the requests. Engine tests run the connectors and delegates in an embedded engine (H2) with
Connect and Spin.

### Design

- `LlmService` holds the models (LangChain4j `ChatModel`s, built once, thread-safe) and the tools.
  The connectors and delegates find it through the engine they run in (`AiLlmPlugin.serviceOf`), so
  the Connect singletons carry no configuration and several engines can use different models.
- Connectors and delegates share one input and output contract (`LlmOperation`).
- The agent loop is explicit (not LangChain4j `AiServices`): every model call counts against
  `maxIterations`, and only tools registered on the plugin and enabled on the task are offered.
- `llm-extract` validates with networknt `json-schema-validator`; the schema is always in the prompt,
  JSON mode is a hint, validation decides.

### Decisions and guesses

Made in best guess while building the extension:

- Template syntax `{{name}}` with dotted paths, failing on unknown placeholders (instead of an empty
  string), so a missing variable surfaces as an error, not as a worse answer.
- API keys are refused as task inputs; any unknown input fails the task (catches typos).
- A failing tool fails the task instead of being reported to the model: it may have changed state in
  the engine transaction (taken from CIB seven).
- Delegates write token usage to `<resultVariable>InputTokens/OutputTokens/TotalTokens`; connectors
  expose it as outputs.
- `json-schema` structured output is sent as a raw `response_format` parameter: LangChain4j parses
  raw schemas with an `ObjectMapper` that registers every Jackson module on the class path, and the
  Scala module from Operaton's FEEL engine turns the schema's arrays into Scala collections.
- `structured-output` other than `prompt` is rejected for Anthropic (those are OpenAI response
  formats).
- Azure OpenAI is supported through its OpenAI-compatible v1 endpoint with the key in the `api-key`
  header (Microsoft Entra tokens are not supported).

### Known limitations

- No streaming, chat memory, RAG or MCP (see [When to use](#when-to-use)).
- Bean tools accept only scalar parameters (strings, numbers, booleans, enums).
- Connector outputs are plain values; an answer over 4000 characters mapped with an output
  parameter is stored as a string variable, which exceeds the column size. Use `resultVariable`
  (stores it as a JSON object value) or limit `maxTokens`.
- Without a JSON serializer (Spin JSON), `resultVariable` with long text or `resultFormat: json`
  fails with an explanation.

### Sources

Parts are ported from CIB seven `connect/ai-agent`
(<https://github.com/cibseven/cibseven/tree/main/connect/ai-agent>, Apache-2.0), with the original
headers kept: `internal/AgentLoop.java` (handling of invented tool names, refused versus failing
tools, message texts) and `internal/LongText.java` (storing answers over 4000 characters as JSON
object values). The rest is written for this repository; the Connect provider registration follows
Operaton's `connect/http-client`. See [NOTICE](NOTICE).

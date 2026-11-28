# Examples

Every extension has `example/`: a runnable, near-real-world Spring Boot application that uses the
extension the way a user would. It follows the style of the
[Operaton examples](https://github.com/operaton/operaton-examples) (self-contained Spring Boot
application, `compose.yaml`, Testcontainers integration tests, Maven Wrapper). It is never released
([module-structure.md](module-structure.md#what-is-released)).

## Scope

- **One extension, one realistic scenario** (for example a Kafka connector inside an order process),
  not a feature tour. The README states the scenario in its first sentence.
- Minimal: no code, dependency or model element that does not serve the scenario.
- Self-contained: depends on the extension's artifact (or starter) and Operaton, never on another
  example or extension. Copy, do not share.

## Layout

```
example/
  pom.xml
  mvnw, mvnw.cmd, .mvn/wrapper/       Maven Wrapper (committed)
  compose.yaml                        external systems the scenario needs
  README.md
  src/main/java/...                   <Name>Application and the scenario's code
  src/main/resources/                 *.bpmn, *.dmn, application.yaml
  src/test/java/...                   *IT classes (Testcontainers)
```

The Java package is `org.operaton.extensions.<kind>.<name without dashes>.example`.

## Rules

- **Models**: BPMN/DMN in `src/main/resources`, `xmlns:operaton="http://operaton.org/schema/1.0/bpmn"`
  (never `camunda`), `isExecutable="true"`, `operaton:historyTimeToLive` set, process id in
  kebab-case equal to the file name, meaningful element names (verb-object for tasks), named
  gateway flows, full BPMN DI so the model opens in Cockpit and bpmn.io.
- **Application**: `@SpringBootApplication` class `<Name>Application`; `application.yaml`; admin
  user `demo/demo` through `operaton.bpm.admin-user`; named seed users (`alice`, `bob`). Code is
  complete, never a stub.
- **`compose.yaml`**: exactly the services the scenario needs (PostgreSQL plus the extension's
  external systems), each with a `healthcheck`, dependents using `depends_on: condition:
  service_healthy`, fixed documented ports, throwaway credentials stated in the README.
  `docker compose up -d` then `./mvnw spring-boot:run` works without further configuration.
  Examples assume they run one at a time.
- **Integration tests** (`*IT`, Failsafe) run the process **end to end**: deploy, start, drive
  through wait states, assert the end event, variable values and the effect on the external system.
  External systems run as real Testcontainers; no mocks of the system the extension integrates
  with (WireMock only if the extension's concept is calling an arbitrary REST API). ITs use
  PostgreSQL via Testcontainers, not H2. Cover the happy path and at least one error path. No
  `Thread.sleep`; use Awaitility.
- Failsafe is declared with `integration-test` and `verify`; check that `Tests run:` is greater
  than zero.

## README

Sections, in this order: title with one-sentence scenario; what it shows; process model (PNG
rendered from the BPMN, no Mermaid); prerequisites (JDK 21, Docker); run it (`chmod +x mvnw`,
`docker compose up -d --wait`, `./mvnw spring-boot:run`, Cockpit URL and credentials); walkthrough
(happy and error path); how it works (links from model elements to code); run the tests
(`./mvnw verify`).

## Differences from operaton-examples

Maven only (no Gradle build); the example's version and parent are the extension aggregator's;
there is no numbering of examples and no root registry: the extension README's `## Example`
section is the entry point.

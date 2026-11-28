# Coding

This document covers the Java extensions (plugins and connectors). Clients follow
[clients.md](clients.md); the error-handling principle (fail loudly) applies to all.

## Language and formatting

- Java 17 source and bytecode (`maven.compiler.release=17`); no preview features.
- Formatting is Palantir Java Format, enforced by Spotless on `verify`. Run
  `./mvnw spotless:apply` before committing. Do not hand-format against the tool.
- No wildcard imports, no unused code, no commented-out code.

## License header

Every source file (Java, and other formats that allow comments) starts with the Apache-2.0 short
header; Spotless adds it.

```java
/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

Ported files keep their original header, see [licensing-and-attribution.md](licensing-and-attribution.md).

## Logging

SLF4J only (`org.slf4j.Logger`, one `private static final Logger` per class), parameterised
messages, no string concatenation. Never log secrets or complete payloads. Levels: `ERROR` only for
conditions that are also thrown or need an operator; `WARN` for recoverable misconfiguration;
`INFO` sparingly (startup, shutdown); `DEBUG` for diagnostics.

## Error handling: fail loudly

- Throw with context (what was attempted, with which identifiers, why it failed) and chain the
  cause. Inside a delegate or listener, let the exception reach the engine so the job or command
  fails visibly, retries and creates an incident. Use `BpmnError` only for modelled business errors.
- Validate configuration at startup and refuse to start on invalid values.
- Forbidden: empty `catch` blocks, `printStackTrace()`, swallowing an exception after logging it,
  returning `null`, empty strings or empty collections as a fallback for a failure, silently
  ignoring unknown or missing required input.
- A stub or unfinished path throws `new UnsupportedOperationException("not implemented: <what>")`.
  It never returns a placeholder value.

## Configuration

- Engine side: properties of the `ProcessEnginePlugin` (setters on the plugin class), documented in
  the README.
- Spring Boot side (starter): `@ConfigurationProperties` with the prefix
  `operaton.extensions.<kind>.<name>.*` (for example `operaton.extensions.connector.kafka.bootstrap-servers`).
  The starter only maps these onto the plugin's properties.
- Secrets are never defaulted and never logged.

## State and concurrency

- No static mutable state. No singletons holding state outside the plugin or bean instance.
- Code called by the job executor, listeners and delegates runs concurrently: make shared
  instances immutable or thread-safe, and document the guarantee on the class.
- Release resources (clients, pools, threads) in the plugin's lifecycle hooks or the bean's destroy
  method.

## Tests

| Kind | Tools | Where |
|---|---|---|
| Unit | JUnit Jupiter (JUnit 6), AssertJ | `src/test/java` of the artifact |
| Engine | Operaton `ProcessEngineExtension` with in-memory H2 | `src/test/java` of the artifact |
| External system | Testcontainers (real system, no mocks of it) | artifact tests or `example/` ITs |

- Test classes `*Test` run in Surefire, `*IT` in Failsafe. Failsafe must be declared wherever ITs
  exist; a build that ran zero ITs is broken.
- Assertions with AssertJ only. No `Thread.sleep`; use Awaitility.
- Every extension is tested on every Operaton minor it declares, see [compatibility.md](compatibility.md).
- Cover the failure paths required above: assert that errors reach the engine.

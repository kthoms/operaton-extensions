# Process Test Coverage

Measures which BPMN flow nodes and sequence flows your process tests execute, asserts a minimum coverage and writes an HTML and JSON report.

## When to use

Use it when you test BPMN processes with Operaton's JUnit 5 `ProcessEngineExtension` and want to know
which paths of a model no test reaches, or want the build to fail when coverage drops below a
threshold. Coverage is computed per process definition, per test class and per test method.

Not for: measuring Java code coverage (use JaCoCo), measuring what happens in production (use
history or tracing), or JUnit 4 and Spring-managed engines (see [Development](#development)).

## Installation

The artifact is a test dependency; the engine and JUnit are provided by your project.

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-process-test-coverage</artifactId>
  <version>&lt;version&gt;</version>
  <scope>test</scope>
</dependency>
```

- **Plain engine / JUnit 5** (the main use): the dependency above, plus `operaton-bpm-junit5` and
  JUnit Jupiter, which you already have when you use `ProcessEngineExtension`. Replace that extension
  by `ProcessTestCoverageExtension`; it installs the plugin itself.
- **Engine built elsewhere**: register `ProcessTestCoveragePlugin` in the engine configuration
  (`processEnginePlugins` in `operaton.cfg.xml`) and use `useProcessEngine(engine)` on the builder.
  The plugin records events only while a test run is active, so it is harmless otherwise.
- **Spring Boot / shared engine**: there is no starter; coverage is a test-time concern. See
  [Development](#development).

## Usage

```java
class InvoiceApprovalTest {

    @RegisterExtension
    static ProcessTestCoverageExtension coverage = ProcessTestCoverageExtension.newBuilder()
            .withMinimumCoverage(0.8)               // whole class, over all its test methods
            .withMinimumMethodCoverage(0.5)         // every method on its own (optional)
            .withMinimumMethodCoverage("smallInvoice", 0.6)   // one method (optional)
            .build();

    RuntimeService runtimeService;   // injected, as with ProcessEngineExtension

    @Test
    @Deployment(resources = "bpmn/invoice-approval.bpmn")
    void smallInvoice() { /* start the process, drive it */ }
}
```

- Register in a **static** field (or `@ExtendWith(ProcessTestCoverageExtension.class)`); an instance
  field is rejected with an `IllegalStateException`, because class coverage needs `@BeforeAll` and
  `@AfterAll`.
- The extension is a `ProcessEngineExtension`: use it instead of that extension, not next to it. It
  accepts the same options (`configurationResource`, `configurator`, `ensureCleanAfterTest`) and gets
  its own engine, closed after the class.
- Coverage of a process counts **flow nodes plus sequence flows** of the executable process; a node
  counts when the engine started it, a flow when it was taken. Only processes that were executed by
  the tests are included: a process that is deployed but never started does not lower the coverage.
  Call activities are covered in the called process's own model.
- `@ExcludeFromProcessCoverage` on a class or method removes it from coverage; process keys are
  excluded with `excludeProcessDefinitionKeys(...)`.
- A failed coverage assertion throws an `AssertionFailedError` that names the subject, the achieved
  and the required percentage, and the coverage of every process. A test method that fails or is
  aborted is not additionally judged on coverage. A minimum with no executed process fails.

### Report

After each test class, `target/process-test-coverage/<fully qualified class name>/` contains:

| File | Content |
|---|---|
| `index.html` | One page: choose "All tests" or a single test method, choose a process; covered elements are green, uncovered red, with percentages and the lists of elements |
| `coverage.json` | The same data: models (ids, BPMN XML), coverage per suite and per run, and every recorded event |

The page renders the BPMN with [bpmn-js](https://bpmn.io) loaded from a CDN (jsDelivr, pinned
version). Without network it shows the lists only. Point it to a mirror with `bpmnJs(scriptUrl,
cssUrl)`.

## Configuration

Builder methods of `ProcessTestCoverageExtension.newBuilder()`:

| Method | Default | Meaning |
|---|---|---|
| `withMinimumCoverage(double)` | none | Minimum class coverage, 0 to 1 |
| `withMinimumMethodCoverage(double)` | none | Minimum coverage of every test method |
| `withMinimumMethodCoverage(String, double)` | none | Minimum coverage of one test method (by method name) |
| `excludeProcessDefinitionKeys(String...)` | none | Process keys that are not recorded |
| `reportDirectory(Path)` | `target/process-test-coverage` | Where the per-class folders go (relative to the working directory) |
| `withoutReport()` | report is written | Skip HTML and JSON |
| `bpmnJs(String, String)` | jsDelivr `bpmn-js@17.11.1` | bpmn-js script and CSS URLs for the report |
| `configurationResource(String)`, `configurator(Consumer)`, `ensureCleanAfterTest(boolean)` | `operaton.cfg.xml` or in-memory H2 | As in `ProcessEngineExtension` |

System properties, used when the builder does not set the value (also for `@ExtendWith`):

| Property | Meaning |
|---|---|
| `operaton.process-test-coverage.minimum-coverage` | Minimum class coverage, 0 to 1; a malformed value fails the test class |
| `operaton.process-test-coverage.report-directory` | Report directory |
| `operaton.process-test-coverage.excluded-process-keys` | Comma separated process keys to exclude |

Plugin property (when registering `ProcessTestCoveragePlugin` yourself): `excludedProcessDefinitionKeys`
(list of keys).

## Compatibility

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): an invoice approval process whose tests together must execute every element;
dropping a test fails the build and the report shows the missing path. Run it with
`../../../mvnw verify` in `example/`, then open
`example/target/process-test-coverage/.../index.html`.

## Development

```bash
./mvnw -B -pl plugins/process-test-coverage -amd verify
./mvnw -B -pl plugins/process-test-coverage/operaton-plugin-process-test-coverage -am verify -Doperaton.version=2.1.6
```

Design:

- `ProcessTestCoveragePlugin` adds a `BpmnParseListener` that attaches an execution listener (`start`)
  to every activity and a `take` listener to every sequence flow, and a `CompensationEventHandler`
  that records compensation boundary events. Recorded events go to the plugin's thread-safe
  `CoverageCollector`, attributed to the active test run.
- The engine does not take the flow behind an event-based gateway and does not run link throw events;
  both are derived from the BPMN model when the following catch event starts.
- Elements of the model are read from the deployed definition when its first event arrives.

Decisions and best guesses:

- Java instead of the original's Kotlin; only JUnit 5 with Operaton's `ProcessEngineExtension`.
  JUnit 4, the Gradle/Maven report aggregators, the Sonar plugin and the Spring Boot starter of the
  original were left out (lean scope).
- No Spring Boot starter: engines in Spring Boot tests are created by the Spring context, which the
  JUnit extension cannot hook into cheaply. Register `ProcessTestCoveragePlugin` as a bean and read its
  `CoverageCollector` if you need that.
- The report is one self-contained HTML file using bpmn-js from a CDN instead of the original's
  bundled React application; there is no cross-class index page.
- The extension creates a private engine (random name) to be sure the plugin is installed; the
  original shared the default engine.
- Tests of one class must run sequentially (the active run is one per engine).

Known limitations: parallel test execution within a class; coverage of elements the engine never
announces (for example a boundary event that is only a compensation trigger is covered through the
compensation handler, other exotic constructs are untested); the report diagram rendering needs a
browser with network access to the CDN (not verified visually in this build, the data is covered by
tests).

### Sources and attribution

The listener design, the compensation handling, the model/coverage calculation and the extension
lifecycle are ported (translated from Kotlin to Java and reworked) from
[camunda-community-hub/camunda-process-test-coverage](https://github.com/camunda-community-hub/camunda-process-test-coverage)
(Apache-2.0, archived). Ported files keep the original copyright header; see [NOTICE](NOTICE).

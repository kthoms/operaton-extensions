# Typed Variables

Declares process variable names and types once and gives delegates, listeners, external task workers and tests type-safe access to them.

## When to use

Process variables are untyped by default: `execution.getVariable("totlaCents")` compiles, returns `null` for the typo and
a `ClassCastException` shows up three activities later. With this extension every variable is declared once, as a constant
with a name and a Java type, and every read and write goes through that declaration:

- a **missing variable fails at the place that needs it**, with a message that names the variable and where it was looked up;
- a **value of another type fails** the same way, instead of being cast somewhere else;
- the **same declaration serves** delegates, execution and task listeners, external task workers, process starters and
  tests, so a rename is a one-line change and the compiler finds every use;
- tests assert on variables with the same constants (`assertThat(processInstance).hasVariable(TOTAL_CENTS, 1999L)`).

Use it when a process has more than a handful of variables or more than one place that touches them. Do not use it for
variables that are really data-driven (names computed at run time, forms with arbitrary fields): read those as a
`VariableMap` or `Map<String, Object>`. It is a library, not an engine plugin: it changes nothing in the engine and only
wraps `VariableScope`, `VariableMap`, the runtime and task services, and the external task client's `ExternalTask`.

The API shape (factories per type with `from` and `on` adapters, a reader and writer pair, a variable map builder) follows
[holunda-io/camunda-bpm-data](https://github.com/holunda-io/camunda-bpm-data) (Apache-2.0, archived). The code here is new
Java 17 code written for Operaton; nothing was ported (see [Development](#development)).

## Installation

Maven (plain engine, Spring Boot, shared engine; there is nothing to register or auto-configure):

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-typed-variables</artifactId>
  <version>VERSION</version>
</dependency>

<!-- optional: AssertJ assertions for tests -->
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-typed-variables-test</artifactId>
  <version>VERSION</version>
  <scope>test</scope>
</dependency>
```

- The engine (`operaton-engine`) is `provided`: your application brings it.
- **JSON objects** need Spin in the application: `operaton-engine-plugin-spin` and
  `operaton-spin-dataformat-json-jackson`, and the Spin plugin registered in the engine (Spring Boot does that when the jars
  are on the classpath). Without Spin, objects, lists and sets are stored with Java serialization.
- **External task workers** need `operaton-external-task-client` on the classpath, and only for the class
  `ExternalTaskVariables`; everything else compiles and runs without it.
- **Shared engine / distribution**: put the jar into the process application (`WEB-INF/lib`) next to the delegates that use it.
- The test module is released separately as `operaton-plugin-typed-variables-test` and brings AssertJ transitively.

## Usage

### Declare the variables once

One class per process (or per bounded set of variables) holds the declarations:

```java
import static org.operaton.extensions.plugin.typedvariables.TypedVariables.*;

public final class OrderVariables {
    public static final VariableFactory<Order> ORDER = customVariable("order", Order.class);          // JSON via Spin
    public static final VariableFactory<OrderStatus> STATUS = enumVariable("status", OrderStatus.class); // stored as string
    public static final VariableFactory<Long> TOTAL_CENTS = longVariable("totalCents");
    public static final VariableFactory<Boolean> VALID = booleanVariable("valid");
    public static final VariableFactory<List<String>> VALIDATION_ERRORS = listVariable("validationErrors", String.class);
    public static final VariableFactory<Date> RECEIVED_AT = dateVariable("receivedAt");
}
```

Declarations are immutable and thread-safe, so they are safe as `static final` constants. Types:
`stringVariable`, `longVariable`, `intVariable`, `doubleVariable`, `booleanVariable`, `dateVariable`, `uuidVariable`,
`enumVariable(name, Class)`, `customVariable(name, Class)`, `listVariable(name, Class)`, `setVariable(name, Class)`, and
`variable(name, VariableCodec)` for a type you convert yourself.

### Delegates and listeners

```java
public class PriceOrderDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
        Order order = ORDER.get(execution);                         // VariableNotFoundException if missing or null
        TOTAL_CENTS.set(execution, order.total());                  // typed value, no boxing mistakes
        VALIDATION_ERRORS.setLocal(execution, List.of());           // local scope
        boolean paid = PAID.from(execution).getOrDefault(false);    // default for an optional variable
        Optional<String> coupon = COUPON.getOptional(execution);
    }
}
```

The shorthands on a factory are `get`, `getOptional`, `getLocal`, `isPresent`, `set`, `setLocal`, `remove`, `removeLocal`
for a `VariableScope` (a `DelegateExecution` or `DelegateTask`), and `get`, `getOptional`, `isPresent`, `set`, `remove` for a
`VariableMap`. `factory.from(...)` returns a `ReadAdapter` and `factory.on(...)` a `WriteAdapter` for every source and sink:

| Source / sink | Factory method |
|---|---|
| `VariableScope` (`DelegateExecution`, `DelegateTask`) | `from(scope)`, `on(scope)` |
| `VariableMap` | `from(map)`, `on(map)` |
| `LockedExternalTask` (engine API) | `from(task)` |
| execution through `RuntimeService` | `from(runtimeService, executionId)`, `on(runtimeService, executionId)` |
| task through `TaskService` | `fromTask(taskService, taskId)`, `onTask(taskService, taskId)` |
| external task of the external task client | `ExternalTaskVariables.of(task)`, see below |
| anything else | implement `VariableSource` / `VariableSink` |

The `ReadAdapter` has `get`, `getOptional`, `getOrDefault`, `isPresent` and the `getLocal...` / `isLocalPresent` variants.

### Semantics

| Situation | Result |
|---|---|
| variable missing, or present with a `null` value | `get` throws `VariableNotFoundException` (variable name, and the execution or task with activity); `getOptional` is empty; `isPresent` is `false` |
| value of another type | `get`, `getOptional` and `getOrDefault` throw `WrongVariableTypeException` (declared type, actual type) |
| `set(scope, null)` | `IllegalArgumentException`: use `remove`. `null` and missing are the same for typed variables |
| local scope on a `VariableMap` or an external task | `UnsupportedOperationException` (they have no local scope) |
| lossless numeric widening | `longVariable` reads `Integer`, `Short` and `Byte`; `intVariable` reads `Long` within the int range; nothing else is converted silently |

All exceptions extend `TypedVariableException` (a `RuntimeException`), so inside a delegate they fail the job, the engine
retries and raises an incident whose message says what is missing.

### Building variable maps

```java
VariableMap variables = TypedVariables.builder()
        .set(ORDER, order)
        .set(STATUS, OrderStatus.RECEIVED)
        .setIfNotNull(COUPON, couponOrNull)
        .build();
runtimeService.startProcessInstanceByKey("order-processing", variables);
taskService.complete(taskId, TypedVariables.builder().set(APPROVED, true).build());
```

### A typed variable set: reader and writer

Extend `VariableReader` and `VariableWriter` to get one accessor per variable, defined once and reused in delegates,
workers and tests:

```java
public final class OrderReader extends VariableReader {
    public OrderReader(VariableScope scope) { super(VariableSources.scope(scope)); }
    public Order order() { return get(ORDER); }
    public boolean paid() { return getOrDefault(PAID, false); }
}

public final class OrderWriter extends VariableWriter<OrderWriter> {
    public OrderWriter(VariableScope scope) { super(VariableSinks.scope(scope)); }
    public OrderWriter status(OrderStatus status) { return set(STATUS, status); }
}

new OrderWriter(execution).status(OrderStatus.ACCEPTED);
```

Without subclassing: `VariableReader.of(execution).get(ORDER)` and `VariableWriter.of(execution).set(STATUS, ...).set(...)`.

### Constants and variable sets

`VariableSet.of(OrderVariables.class)` collects the declarations of a constants class (static `VariableFactory` fields);
`VariableSet.of(ORDER, TOTAL_CENTS)` builds one by hand. It lists the names (for `fetchVariables`, external task
subscriptions, `operaton:in` mappings) and checks that a source holds all of them, naming **every** missing variable in one
message:

```java
VariableSet.of(OrderVariables.class).requirePresent(execution);
```

The names are plain strings in the engine, so BPMN expressions keep working: `${valid}`, `${needsApproval}`.

### External task workers

`ExternalTaskVariables` wraps the client's `ExternalTask`. It is a `VariableReader` and a `VariableSource`:

```java
client.subscribe("price-order")
        .variables(INPUT.getNamesArray())                // INPUT = VariableSet.of(ORDER, ...)
        .handler((task, service) -> {
            ExternalTaskVariables variables = ExternalTaskVariables.of(task);
            Order order = variables.get(ORDER);
            service.complete(task, TypedVariables.builder().set(TOTAL_CENTS, price(order)).build());
        })
        .open();
```

Only subscribed variables are on the task; asking for another one fails with `VariableNotFoundException`.

### How objects are stored

| Declaration | `SerializationFormat.AUTO` (default) | `JSON` | `JAVA` |
|---|---|---|---|
| `customVariable(name, Class)` | JSON with Spin when present, else Java serialization | JSON; fails at declaration if Spin is missing | Java serialization; the class must be `Serializable` |
| `listVariable(name, Class)` | same, as `ArrayList<E>` (element type recorded) | | |
| `setVariable(name, Class)` | same, as `LinkedHashSet<E>` (insertion order) | | |

`null` elements are refused. Each declaration takes the format as an extra argument:
`customVariable("order", Order.class, SerializationFormat.JAVA)`. Maps are not offered as a declaration because the engine
cannot record their key and value types in JSON; wrap a map in a class.

### Tests

`operaton-plugin-typed-variables-test` adds AssertJ assertions that work with `ProcessEngineExtension`:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.operaton.extensions.plugin.typedvariables.test.TypedVariablesAssertions.assertThat;

assertThat(processInstance)                       // finds the one engine registered in ProcessEngines
        .hasVariable(ORDER_ID)
        .hasVariable(TOTAL_CENTS, 1999L)
        .hasVariableSatisfying(VALIDATION_ERRORS, errors -> assertThat(errors).isEmpty())
        .hasNoVariable(APPROVED)
        .hasAllVariables(VariableSet.of(OrderVariables.class));
assertThat(engine, processInstance).variable(TOTAL_CENTS, InstanceOfAssertFactories.LONG).isGreaterThan(0L);
assertThat(engine, historicProcessInstance).hasVariable(STATUS, OrderStatus.ACCEPTED);   // after the process ended
assertThat(executionInSubProcess).hasLocalVariable(STEP, "packing");
assertThat(completionVariables).hasVariable(TOTAL_CENTS, 400L);                          // a VariableMap
```

Sources: `ProcessInstance` / `Execution`, `HistoricProcessInstance` (history level `audit` or `full`), `VariableScope`,
`VariableMap`, any `VariableSource`. A missing variable, a different value and a wrong type each fail with a message that
names the variable and the source. The overloads without an engine use the single engine registered in `ProcessEngines`
and refuse to guess when there are several; pass the engine (`ProcessEngineExtension` or `ProcessEngine`) as first
argument then.

## Configuration

There is nothing to configure: no engine plugin, no properties, no Spring Boot auto-configuration. The only choice is the
storage format of objects, lists and sets, made per declaration with `SerializationFormat` (see
[How objects are stored](#how-objects-are-stored)).

| Property | Default | Meaning |
|---|---|---|
| none | | |

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | 2.0.x (latest patch) |
| 2.1 | 2.1.x (latest patch) |
| 2.2 | 2.2.x (latest patch) |

## Example

[`example/`](example/): an order-processing Spring Boot application on PostgreSQL in which one `OrderVariables` class is
used by two delegates, an execution listener, the service that starts the process and the integration test.

## Development

```bash
./mvnw -B -pl plugins/typed-variables -amd verify                                    # both modules and the example (Docker for the IT)
./mvnw -B -pl plugins/typed-variables/operaton-plugin-typed-variables -am verify -Doperaton.version=2.1.6   # another Operaton version
```

On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

### Layout and decisions

- **Two released modules.** `operaton-plugin-typed-variables` (the API, `provided` engine, no AssertJ) and
  `operaton-plugin-typed-variables-test` (AssertJ assertions). The split keeps AssertJ off the production classpath and
  lets the assertions evolve without touching the API. There is **no Spring Boot starter**: nothing needs registering or
  configuring, and the declarations are static constants. A starter would only be a dependency that re-exports the jar.
- **No engine plugin.** The extension is kind `plugin` for the catalog, but it is a library. It has no
  `ProcessEnginePlugin` because it changes no engine behaviour.
- **The external task client is isolated.** `ExternalTaskVariables.of(task)` replaces an `ExternalTask` overload on the
  factory. A method taking `ExternalTask` on `VariableFactory` would make `javac` need the client jar for every delegate
  that calls `ORDER.get(execution)` (overload resolution loads all candidate signatures). The test module compiles
  and runs against the API without the client on its classpath, which keeps that true.
- **`DelegateExecution` is a `VariableScope`**, so there is no separate overload for it.
- **Null equals missing.** Operaton can store a typed `null`; this extension does not: `get` fails, `getOptional` is
  empty, `set(null)` is refused. A required value that is `null` is a bug at the place that needs it.
- **Strict types.** Only lossless integral widening happens on reads; an `Integer` is not turned into a `Double`, a `Long`
  outside the int range is not narrowed.
- **JSON is chosen by classpath.** `AUTO` checks for `SpinProcessEnginePlugin` and `JacksonJsonDataFormat`. It cannot see
  whether the *engine* has the plugin registered; if it has not, the engine's own "cannot find serializer" message surfaces
  on the first write. `JAVA` can be requested explicitly. The no-Spin path is tested in its own Surefire execution
  (`NoSpin*Test`) with the Spin artifacts removed from the classpath.
- **Elements of JSON lists** are checked against the declared class on read; a JSON list written without type
  information (by another system) comes back as maps and fails with a hint, rather than a `ClassCastException` later.
- **Thread-safety.** Factories, codecs, adapters, readers and writers hold only immutable references; they are as
  thread-safe as the scope, map or service they wrap. A `VariableMapBuilder` is single-threaded.

### Sources and attribution

The API shape was taken from [holunda-io/camunda-bpm-data](https://github.com/holunda-io/camunda-bpm-data)
(`gh api repos/holunda-io/camunda-bpm-data -q .license.spdx_id` returns `Apache-2.0`; the repository is archived). That
project is Kotlin; this extension is written from scratch in Java and **no code was ported**, so there is no extension
`NOTICE` and no root `NOTICE` entry. Differences in design: variables are `null`-free, the external task client is
isolated, there is a `VariableSet`, objects are stored per `SerializationFormat`, and the test support is AssertJ based and
works with `ProcessEngineExtension`. Not carried over: the guard/ACL features, case management (CMMN) variable
accessors, map and list-of-maps factories.

### Known limitations

- `ExternalTaskVariables` reads JSON objects as the client delivers them: the client must be able to deserialize the
  stored class (it is on the worker's classpath). A serialized value that is not deserialized fails with a clear message.
- Historic assertions see the variables of the process instance scope only; variables of sub process scopes are not part
  of `HistoricProcessInstance` lookups.
- Maps and CMMN case variables have no declaration (see above).
- The example is verified on Operaton 2.2 (the repository default); the artifacts are verified on 2.0, 2.1 and 2.2.

# JDBC connector

Run parameterised SQL queries and updates against relational databases from BPMN processes.

## When to use

Use it when a process needs to read or write rows in a business database: look up a customer, insert an
order, update a status. SQL uses **named parameters** (`:customerId`) that are always bound through
`PreparedStatement`, never concatenated into the text, so a value cannot change the statement.

Connector ids: `jdbc-query` (rows, a single row or a single value) and `jdbc-update` (update count, optional
generated keys). A `JavaDelegate` alternative exists for each.

Do not use it for bulk data movement or long-running reports (results become process variables; the default
limit is 1000 rows), for stored-procedure calls with OUT parameters, or for the engine's own database (use the
engine APIs). For multi-statement transactions write a Java delegate with your own transaction handling.

### Transactions

| Mode | Behaviour |
|---|---|
| Default | Each call takes its own pooled connection in auto-commit mode and commits at once. It is not part of the engine transaction: if the engine later rolls back (a failing task, an optimistic locking conflict, a job retry), the SQL stays committed. Make writes idempotent, or model compensation. |
| `participate-in-engine-transaction: true` | The SQL runs on the engine's own JDBC connection and commits or rolls back with the engine. Only valid for the DataSource the engine itself uses (checked at startup: the data source must be that DataSource or wrap/unwrap to it, so a business schema in the engine's database works). With Spring the engine's connection is the Spring-managed one. Calls outside an engine command fail. |

## Installation

Plain engine:

```xml
<dependency>
  <groupId>org.operaton.connector.jdbc</groupId>
  <artifactId>jdbc-connector</artifactId>
  <version>VERSION</version>
</dependency>
```

You also need the Operaton Connect plugin (`operaton-engine-plugin-connect`) and your JDBC driver. Register the
plugins in the engine configuration:

```java
JdbcDataSourceProperties business = new JdbcDataSourceProperties();
business.setUrl("jdbc:postgresql://localhost/business");
business.setUsername("app");
business.setPassword(password);

JdbcConnectorPlugin jdbc = new JdbcConnectorPlugin();
jdbc.setDataSources(Map.of("default", business));
configuration.getProcessEnginePlugins().add(new ConnectProcessEnginePlugin());
configuration.getProcessEnginePlugins().add(jdbc);
// after engine.close(): jdbc.close()
```

Spring Boot:

```xml
<dependency>
  <groupId>org.operaton.connector.jdbc</groupId>
  <artifactId>jdbc-connector-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

Shared engine / distribution: put the jar, HikariCP and the driver in the shared `lib/` and add
`org.operaton.extensions.connector.jdbc.JdbcConnectorPlugin` to the engine's `<plugins>` in `bpm-platform.xml` with
JNDI data sources (`jndi-name`). Java properties of the plugin: `dataSources` (map of `JdbcDataSourceProperties`)
and `dataSourceInstances`.

## Usage

### Query

```xml
<serviceTask id="lookUpCustomer" name="Look up customer">
  <extensionElements>
    <operaton:connector>
      <operaton:connectorId>jdbc-query</operaton:connectorId>
      <operaton:inputOutput>
        <operaton:inputParameter name="sql">SELECT id, name, credit_limit FROM customer WHERE id = :customerId</operaton:inputParameter>
        <operaton:inputParameter name="customerId">${customerId}</operaton:inputParameter>
        <operaton:inputParameter name="resultFormat">SINGLE_ROW</operaton:inputParameter>
        <operaton:outputParameter name="customer">${result}</operaton:outputParameter>
      </operaton:inputOutput>
    </operaton:connector>
  </extensionElements>
</serviceTask>
```

Every input that is not one of the names in the tables below is a named SQL parameter; alternatively pass them in a
`parameters` map (`<operaton:map>`). A name in both is an error, and a `:name` in the SQL without a value fails
before anything runs. A list (or array) value expands for `IN (:ids)`. The SQL may contain only one statement and no
`?` markers; colons inside string literals, quoted identifiers, comments, `$$` quotes and `::` casts are not parameters.

**SQL injection.** Values only travel as bound parameters. The one thing that is not protected is the SQL text itself:
do not build it from process data (`${...}` concatenation). The delegates enforce this by accepting only a literal `sql`
field.

### Update

```xml
<operaton:connectorId>jdbc-update</operaton:connectorId>
<operaton:inputOutput>
  <operaton:inputParameter name="sql">INSERT INTO sales_order (customer_id, total) VALUES (:customerId, :orderTotal)</operaton:inputParameter>
  <operaton:inputParameter name="customerId">${customerId}</operaton:inputParameter>
  <operaton:inputParameter name="orderTotal">${orderTotal}</operaton:inputParameter>
  <operaton:inputParameter name="generatedKeyColumns">id</operaton:inputParameter>
  <operaton:outputParameter name="orderId">${generatedKey}</operaton:outputParameter>
</operaton:inputOutput>
```

### Delegates

```xml
<serviceTask id="lookup" operaton:class="org.operaton.extensions.connector.jdbc.delegate.JdbcQueryDelegate">
  <extensionElements>
    <operaton:field name="sql"><operaton:string>SELECT * FROM customer WHERE id = :customerId</operaton:string></operaton:field>
    <operaton:field name="resultFormat"><operaton:string>SINGLE_ROW</operaton:string></operaton:field>
    <operaton:field name="resultVariable"><operaton:string>customer</operaton:string></operaton:field>
  </extensionElements>
</serviceTask>
```

Fields are the connector inputs (see below) plus `resultVariable`, `rowCountVariable`, `truncatedVariable`
(`JdbcQueryDelegate`) and `updateCountVariable`, `generatedKeyVariable`, `generatedKeysVariable`
(`JdbcUpdateDelegate`). A SQL parameter not in the `parameters` field is read from the process variable of the same
name; if there is none the delegate fails.

### Inputs of `jdbc-query`

| Input | Default | Meaning |
|---|---|---|
| `sql` | required | Statement with `:named` parameters. |
| `datasource` | `default` | Name of the data source. |
| `parameters` | | Map of parameter values. |
| `parameterTypes` | | Map name to type (`STRING, INTEGER, BIGINT, DECIMAL, DOUBLE, BOOLEAN, DATE, TIME, TIMESTAMP, TIMESTAMP_TZ, UUID, JSON, BINARY`); converts text values and types `null`s. Without it the type follows the Java type of the value. |
| `resultFormat` | `LIST` | `LIST` (list of maps), `SINGLE_ROW` (map), `SINGLE_VALUE` (the scalar). Single formats fail for zero or several rows. |
| `allowEmpty` | `false` | Single formats: zero rows gives `null` instead of failing. |
| `emptyResultErrorCode` | | Zero rows throws a BPMN error with this code (catch with an error boundary event). |
| `maxRows` | data source `default-max-rows` | Row limit. |
| `truncate` | `false` | `false`: more rows than `maxRows` fail; `true`: return the first rows, `truncated=true`. |
| `timeout` | data source `default-query-timeout` | Seconds; `0` none. |
| `columnCase` | `as_is` | `lower`, `upper` or `as_is` (PostgreSQL reports lower case, H2 upper case). |
| `resultAs` | `java` | `json`: Spin JSON (needs `operaton-spin-core` and the Jackson data format). |
| `decimalsAsStrings` | `true` | JSON only: decimals as strings, because Spin's JSON parser reads numbers as double. |

Outputs: `result`, `rowCount`, `truncated`.

### Inputs of `jdbc-update`

`sql`, `datasource`, `parameters`, `parameterTypes`, `timeout`, `columnCase` as above, plus `returnGeneratedKeys`
(boolean) and `generatedKeyColumns` (list or comma separated names; implies `returnGeneratedKeys`). Outputs:
`updateCount`, `generatedKeys` (list of maps) and `generatedKey` (first column of the first row).

### Result types

`DECIMAL`/`NUMERIC` give `BigDecimal` (never double), `BIGINT` `Long`, smaller integers `Integer`, `DOUBLE` `Double`,
`DATE` `LocalDate`, `TIME` `LocalTime`, `TIMESTAMP` `LocalDateTime`, `TIMESTAMP WITH TIME ZONE` `OffsetDateTime`,
text and CLOB `String`, binary `byte[]`, `UUID` `UUID`, SQL arrays `List`, anything else (for example `jsonb`) its text.
Rows are `LinkedHashMap`s, so the variables serialize with the default Java serialization.

### Errors

Failures are thrown as `JdbcConnectorException` (with `getSqlState()`), reach the engine, and create an incident. The
message names the data source and SQL state, never the bound values.

## Configuration

Spring Boot properties, prefix `operaton.extensions.connector.jdbc` (`JdbcDataSourceProperties` in plain Java).

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Enable the auto-configuration. |
| `datasources.<name>.url` | | JDBC URL; the connector creates a HikariCP pool. |
| `datasources.<name>.username`, `.password`, `.driver-class-name` | | Credentials and driver (optional with JDBC 4 drivers). |
| `datasources.<name>.jndi-name` | | Use a JNDI `DataSource` instead. |
| `datasources.<name>.bean-name` | | Use a Spring `DataSource` bean (explicit; no bean is reachable unless named here). |
| `datasources.<name>.driver-properties` | | Map of driver properties. |
| `datasources.<name>.maximum-pool-size` | `10` | Pool size. |
| `datasources.<name>.minimum-idle` | pool size | Idle connections. |
| `datasources.<name>.connection-timeout` | `30s` | Wait for a connection. |
| `datasources.<name>.idle-timeout` / `.max-lifetime` / `.validation-timeout` | `10m` / `30m` / `5s` | Pool lifecycle. |
| `datasources.<name>.initialization-fail-timeout-millis` | `-1` | Negative: the engine starts even if the database is down; positive: fail at startup after that time. |
| `datasources.<name>.read-only`, `.schema` | `false`, | Connection settings. |
| `datasources.<name>.default-query-timeout` | `30s` | Statement timeout when a call sets none; `0` none. |
| `datasources.<name>.default-max-rows` | `1000` | Row limit when a call sets none. |
| `datasources.<name>.participate-in-engine-transaction` | `false` | See Transactions. |

Exactly one of `url`, `jndi-name`, `bean-name` per data source; invalid settings stop the startup. The data source
used by a call is its `datasource` input, default `default`.

Several engines in one JVM each use their own plugin's data sources (the Connect SPI keeps one connector instance per
JVM, so the plugin registers its data sources per engine name).

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | 2.0.0 |
| 2.1 | 2.1.6 |
| 2.2 | 2.2.2 |

## Example

[`example/`](example/): an order-intake process looks up a customer in a business database and inserts the order,
with PostgreSQL holding both the engine database and a second `business` database.

```bash
cd example
docker compose up -d --wait
../../../mvnw spring-boot:run    # Cockpit http://localhost:8080 (demo / demo)
../../../mvnw verify             # Testcontainers end-to-end tests
```

## Development

```bash
./mvnw -B -pl connectors/jdbc -amd verify                                   # needs Docker for the PostgreSQL tests
./mvnw -B -pl connectors/jdbc/jdbc-connector -am verify -Doperaton.version=2.1.6
```

Tests: parser and binding tests, H2 tests (types, limits, injection attempts), engine tests with
`ProcessEngineExtension` (connectors, delegates, transactions), PostgreSQL via Testcontainers, a Spring Boot starter
test.

Design notes and best-guess decisions:

- Fresh implementation; nothing ported (Camunda 8's JDBC connector is not reusable).
- The Connect SPI creates connectors with `ServiceLoader`, so they cannot receive configuration. `JdbcConnectors` is the
  one static, thread-safe holder, keyed by engine name, filled by `JdbcConnectorPlugin`.
- Participation uses the engine's MyBatis connection (`DbSqlSession`, engine internal API). The engine rolls back a
  session only if it wrote itself and closing it would commit, so the connector registers an explicit rollback listener.
- Pools are created by the plugin and live until `plugin.close()` (Spring: bean destroy). The engine has no hook for
  closing plugins.
- `resultFormat`/`maxRows` fail loudly instead of truncating silently; `allowEmpty` and `truncate` are explicit opt-ins.
- Known limits: the `sql` input of the connector can be an expression (the SPI cannot tell), so the SQL text must be
  authored in the model; no stored procedure OUT parameters; no multi-statement scripts.

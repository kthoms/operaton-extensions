# Variable Encryption Plugin

Encrypts selected process variables at rest with AES-256-GCM, transparently to the engine API, including their history.

## When to use

Use it when process variables hold personal or confidential data (an IBAN, a birth date, a tax id, a
document scan) that must not be readable to someone with access to the database, a database dump or a
backup, but that the process, its delegates and its users keep reading through the normal engine API
(`getVariable`, Tasklist, Cockpit, REST). Nothing in the models or the delegates changes.

How it works: the plugin wraps the engine's variable serializers (String, Long, Double, Boolean, Short,
Integer, Date, byte[], File, Object, and the Spin JSON/XML and Spin object serializers). A variable that
is designated for encryption is serialized by the wrapped serializer as usual, the result is encrypted
and stored instead. Reading decrypts and hands the result to the wrapped serializer. The engine copies the
stored columns of a variable row to its history (variable instances and variable updates), so the
**history holds the same ciphertext**; there is no second code path to forget.

### What is covered

| Where | Encrypted |
|---|---|
| `ACT_RU_VARIABLE` (execution, process and task variables) | yes: value, string length limit of 4000 characters no longer applies |
| `ACT_HI_VARINST` (`HistoricVariableInstance`) | yes (copy of the runtime ciphertext) |
| `ACT_HI_DETAIL` (variable updates, `HistoricVariableUpdate`) | yes (copy of the runtime ciphertext) |
| `ACT_GE_BYTEARRAY` (the envelope itself, runtime and history) | holds ciphertext only |
| Decision instance inputs and outputs (history) | use the same serializers: encrypted when their name (input label, output name) matches a pattern; not covered by the tests |

What the encrypted payload contains: the value and everything the serializer stores next to it (for objects the
Java type name, for files the file name, mime type and encoding, for strings the full text).

### What is not covered

* **Metadata stays readable**: variable names, the type (the stored serializer name: `string`, `long`,
  `spin://application/json`, ...), existence, timestamps, process and activity ids, and approximately the
  size of the value (the envelope adds 40 to 60 bytes; short and long values are distinguishable).
* **Everything outside variable values**: business keys, task and process names and descriptions, user
  operation log, incident and job messages and stack traces, external task error details, and anything
  your delegates write to logs or other systems. Do not put personal data there.
* **Data in transit and in memory**: values are plaintext after decryption, for the API caller, in REST
  responses, for external task workers fetching variables, in Cockpit and Tasklist for users who may see them.
  Authorization stays the job of the engine's authorization service.
* **Other consumers of the raw columns**: a history event handler that publishes variable values (for
  example the History Kafka plugin with `include-variable-values`) sees the stored marker and not the value,
  because the events carry the raw columns; reporting tools that read the tables directly see ciphertext.
* **Queries by value**: a designated variable cannot be compared in a query. `variableValueEquals(...)` and the
  other value filters on process instance, task, execution, variable instance and historic queries throw
  a `VariableEncryptionException` naming the variable, never an empty result. Querying by variable *name*
  works; sorting by the value is meaningless. Variables that are not designated are queried as before.
* **Variables written before the plugin was enabled** stay plaintext until they are written again, see
  [migrating existing data](#migrating-existing-data).
* **Every engine on the same database needs the plugin and the keys.** An engine without the plugin reads the
  stored marker text instead of the value and writes plaintext when a variable is set.
* **A process application that registers its own serializer under the same name** as an engine serializer
  replaces the encrypting wrapper for that process application.

## Installation

**Spring Boot** (Operaton Spring Boot starter 2.x):

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-plugin-variable-encryption-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

then set the properties, see [Configuration](#configuration). Without a key the application does not start.

**Plain engine**: add `operaton-plugin-variable-encryption` and register the plugin:

```java
VariableEncryptionPlugin plugin = new VariableEncryptionPlugin();
plugin.setKeys(Map.of("2026-01", System.getenv("VARIABLE_KEY")));   // Base64, 32 bytes
plugin.setActiveKeyId("2026-01");
plugin.setVariableNames(List.of("iban", "birthDate", "applicant*"));
configuration.getProcessEnginePlugins().add(plugin);
```

or in `operaton.cfg.xml`:

```xml
<property name="processEnginePlugins"><list>
  <bean class="org.operaton.extensions.plugin.variableencryption.VariableEncryptionPlugin">
    <property name="keys"><map><entry key="2026-01" value="env:VARIABLE_KEY"/></map></property>
    <property name="activeKeyId" value="2026-01"/>
    <property name="variableNames"><list><value>iban</value><value>birthDate</value></list></property>
  </bean>
</list></property>
```

The plugin installs itself when the engine has been built, so it also wraps the serializers other plugins
(Spin) register; the order in the plugin list does not matter. Spin is optional: JSON and XML values are
encrypted when the Spin plugin is present, and `EncryptedVariables.encrypted(...)` tags them only then.

**Shared engine / distribution**: put the jar into the container's `lib/` and add the plugin class and its
properties to the `process-engine` element of `bpm-platform.xml`.

## Usage

Nothing changes in the models or the delegates. Designate variables by **name** or tag **values**.

**By name** (`variable-names`): globs (`iban`, `applicant*`, `?ecret`) or `regex:` expressions, matched against
the whole, case-sensitive name. Every value written under such a name is encrypted, whatever the code does,
whether it is set by a delegate, a REST call, a form or a message.

**By tag** for single values, whatever the variable is called:

```java
runtimeService.setVariable(instanceId, "passportNumber", EncryptedVariables.encrypted("C01X00T47"));

VariableMap variables = Variables.createVariables()
    .putValueTyped("document", EncryptedVariables.encrypted(
        Variables.fileValue("passport.pdf").file(bytes).create()))
    .putValueTyped("profile", EncryptedVariables.encrypted(
        Variables.objectValue(profile).serializationDataFormat(Variables.SerializationDataFormats.JSON).create()));
```

`encrypted(...)` accepts strings, booleans, numbers, dates, byte arrays, file values, object values and
(with Spin) JSON and XML values, as plain objects or typed values. A tagged value is an ordinary typed
value of its own type; the tag only matters while it is written. The tag applies to **that write**: set the
variable again without it, under a name no pattern covers, and it is stored in plaintext. Use name patterns
for variables that must always be encrypted.

Reading is the normal API; the values come back as they were put in:

```java
String iban = (String) runtimeService.getVariable(instanceId, "iban");
ObjectValue profile = runtimeService.getVariableTyped(instanceId, "applicantProfile");
HistoricVariableInstance old = historyService.createHistoricVariableInstanceQuery().variableName("iban").singleResult();
```

What the database holds (the example prints it for a running application):

| column | plain variable | encrypted variable |
|---|---|---|
| `TYPE_` | `string` | `string` |
| `TEXT_` | `DE89370400440532013000` | `$operaton-ve$1$2026-01` (marker: format version 1, key id) |
| `LONG_`, `DOUBLE_`, `TEXT2_` | the value | empty |
| `BYTEARRAY_ID_` | empty | the envelope in `ACT_GE_BYTEARRAY` |

### Cryptography

* **AES-256-GCM**, 128-bit tag, a **random 96-bit IV for every value** (`SecureRandom`). A key encrypts safely
  up to about 2^32 values; rotate keys well before that.
* **Envelope**: `'O' 'V' 'E'`, format version (1), key id length, **key id**, IV, ciphertext and tag. The key id
  and the version are part of the stored data, so every value names the key that decrypts it.
* **Authenticated context**: header (version, key id) and the **variable name** are additional authenticated
  data. A ciphertext copied to a variable with another name, or an edited key id, fails the integrity check.
  It does not bind a value to its process instance: rows can still be swapped between instances.
* **Fail loudly**: a wrong key, an unknown key id, a modified, truncated or foreign envelope, a marker without
  data, a plaintext row where an encrypted one is required: all throw `VariableEncryptionException` (or its
  subtype `UnknownKeyException`) naming the variable and the key id, never the value or the key. There is no
  fallback to plaintext. A failing write fails the command and stores nothing.
  Values and keys are never logged; the plugin logs key ids and name patterns at startup.
* **Queries load values leniently**: `VariableInstanceQuery`, `HistoricVariableInstanceQuery` and historic detail
  queries catch the failure of one value, log it and expose it as `getErrorMessage()` with a `null` value
  (the engine does this for every variable it cannot deserialize). `getVariable`, `getVariables`, task and
  execution variable access, delegates and expressions throw.

### Keys and rotation

A **key id** is 1 to 64 characters of `A-Z a-z 0-9 . _ -`. A `KeyProvider` returns the key for new
encryptions (`activeKeyId()`) and any key that still protects stored data (`key(id)`).

* `StaticKeyProvider`: keys from configuration, as Base64 of 32 random bytes (`openssl rand -base64 32`),
  or `env:NAME` to read the Base64 from an environment variable.
* `KeyStoreKeyProvider`: a JCE key store (PKCS12 or JCEKS); each AES secret key entry is a key, its alias the
  key id (PKCS12 aliases are lower case). Create one with
  `keytool -genseckey -alias 2026-01 -keyalg AES -keysize 256 -storetype PKCS12 -keystore keys.p12`.
  Call `reload()` after adding a key to the file to avoid a restart.
* **Your own KMS**: implement `KeyProvider` (two methods) on top of a vault or cloud KMS client, cache the
  data keys, and pass it with `setKeyProvider(...)` or as a Spring bean (the starter uses a unique
  `KeyProvider` bean in place of the configured keys). No cloud KMS client is included.

```java
public class VaultKeyProvider implements KeyProvider {
    public String activeKeyId() { return config.currentKeyName(); }
    public SecretKey key(String id) { return cache.get(id, () -> new SecretKeySpec(vault.dataKey(id), "AES")); }
}
```

**Rotation**: add a new key, make it the active one, deploy everywhere, and keep the old keys. New and updated values
use the new key, old values stay readable with the key id they carry. Rows are not rewritten in bulk:
a value moves to the new key when it is written again (and mutable object/JSON values when they are
written again with changed content). History rows are copies and keep the key they were written with. Remove
a key only when nothing encrypted with it is left, or its values fail loudly with `UnknownKeyException`. The
database shows the key of every row: `select NAME_, TEXT_ from ACT_RU_VARIABLE where TEXT_ like '$operaton-ve$1$2026-01%'`.

### Migrating existing data

Designated variables that are stored as plaintext are refused when read (a defence against a row replaced
by plaintext), so enabling the plugin on a running system needs a migration step:

1. set `allow-plaintext-read: true` and start the plugin;
2. read and set each existing variable again (a small script or delegate: `setVariable(name, getVariable(name))`);
3. set `allow-plaintext-read` back to `false`.

History written before the plugin was enabled stays plaintext until it is removed by history cleanup.

## Configuration

Spring Boot prefix `operaton.extensions.plugin.variable-encryption.`; the plugin's setters have the same
names in camelCase. Invalid values stop startup.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Register the plugin (Spring Boot only). |
| `keys.<id>` | none | Static keys: key id to Base64 key (32 bytes) or `env:NAME`. |
| `active-key-id` | none, required with `keys` or `key-store` | Key id for new encryptions. |
| `key-store.path` | none | JCE key store file; alternative to `keys`. |
| `key-store.type` | `PKCS12` | Key store type. |
| `key-store.password` | none, required with `key-store.path` | Store and key password, or `env:NAME`. |
| `variable-names` | empty | Globs or `regex:` patterns of the variable names to encrypt. |
| `encrypt-marked-values` | `true` | Encrypt values tagged with `EncryptedVariables.encrypted(...)`. |
| `allow-plaintext-read` | `false` | Read designated variables that are stored in plaintext (migration only). |

Plugin API extras: `setKeyProvider(KeyProvider)` (instead of `keys`/`key-store`, the settings are mutually
exclusive), `validate()` (checks the configuration and opens the key source; the starter calls it at startup).
Exactly one key source is required; nothing is encrypted if neither `variable-names` nor `encrypt-marked-values`
selects anything, which is refused at startup.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): a Spring Boot account-opening process of an online bank. The applicant's IBAN, birth
date and profile (a JSON object) are encrypted by name, the passport number by tag. The Testcontainers IT
runs the process on PostgreSQL and reads the raw rows through JDBC: ciphertext in `ACT_RU_VARIABLE`,
`ACT_HI_VARINST`, `ACT_HI_DETAIL` and `ACT_GE_BYTEARRAY`, plain values through the engine API, a tampered
ciphertext detected. Run it with `docker compose up -d --wait`, `../../../mvnw spring-boot:run` (from
`plugins/variable-encryption/example`) and `./mvnw verify` for the IT.

## Development

```bash
./mvnw -B -pl plugins/variable-encryption -amd verify            # the example IT needs Docker (Colima: export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock)
./mvnw -B -pl plugins/variable-encryption/operaton-plugin-variable-encryption -am verify -Doperaton.version=2.1.6
```

Tests: unit tests of the envelope (every flipped byte is detected, random IVs, rotation, wrong key, name binding), the
key providers and the name patterns; engine tests on an embedded engine with H2 that read the raw columns through
JDBC (ciphertext and no plaintext substring in the runtime, history and byte array tables), round-trip every
value type through the API, history, tagged values, tampering, plaintext replacement, concurrency, JSON/XML/object
values including implicit in-place updates, key rotation, wrong key, migration, key store and custom key provider
(engines built one after the other on one database); starter tests with `ApplicationContextRunner`.

Design notes and best-guess decisions:

* **Serializer selection has no variable name.** The engine chooses a serializer from the value alone, so a
  "serializer for these variable names" cannot be registered next to the standard ones. The plugin therefore
  replaces every standard serializer by a wrapper with the same name and type and decides in `writeValue`
  and `readValue`, where `ValueFields.getName()` is available. The stored serializer name does not change, so rows
  written before the plugin existed stay readable, and the wrapper uses only the `ValueFields` and
  `TypedValueSerializer` interfaces. Serializers registered later are wrapped through
  `postProcessEngineBuild`; the fallback factory of the Spin plugin is wrapped too.
* **Tagging works through subclasses** of the engine's typed value classes (a tagged `StringValue` is a
  `StringValue`), so the standard serializer is selected and the same-command read returns a value of the
  expected type.
* **Marker and envelope storage.** The envelope goes to the byte array (any length, any type), the text column
  carries a marker with version and key id so that rows are recognizable and `like`-queryable by key. A marker
  without data, or with a different key id than the envelope, is rejected.
* **Mutable values.** The engine detects in-place changes of deserialized objects and JSON by serializing again
  into a name-less scratch record and comparing bytes; a random IV would make every read look like a change.
  An identity-keyed weak memo remembers the envelope of a value instance and reproduces it while the plaintext
  digest is unchanged. A changed value is encrypted with a fresh IV. The memo holds a SHA-256 digest of the
  payload and the envelope, never the plaintext.
* **Transient variables** are not encrypted (they are never stored). Null values of designated variables
  are stored as an envelope too, so that a plaintext row is always recognizable as tampering.
* **Strict plaintext policy by default** (`allow-plaintext-read=false`): a decision for security over a smooth
  first start; the error message names the migration switch.
* **Name binding in the AAD**: any reader that resolves the value under a different name fails. All engine
  readers (runtime, history variable instance, history detail) use the variable name; decision input
  and output values are named by their clause label.
* Not done: bulk re-encryption, a bulk migration job, encryption of anything but values, cloud KMS
  clients, per-instance binding. Operaton 1.x is out of scope.
* Sources: the design follows the engine's `TypedValueSerializer`, `ValueFields`, `TypedValueField`
  and history event producer (Apache-2.0, read, nothing copied), so there is no NOTICE entry.

# S3 Connector

Stores, fetches, lists, copies, deletes and presigns objects on S3-compatible storage from BPMN processes.

## When to use

Use it when a process has to keep documents or other binary results outside the engine database: upload a file variable, hand a reviewer a time-limited download link, archive or delete the object when the process decides. It works with AWS S3 and with S3-compatible stores (MinIO, Ceph, SeaweedFS, LocalStack) through an endpoint override and path-style access.

Not for moving large files through process variables: `s3-get` loads the object into memory and the engine database, so it fails above a configurable size (default 10 MiB). Give users or systems a presigned URL (`s3-presign`) instead. There is no multipart upload and no streaming; uploads read the source variable fully into memory.

## Installation

The connectors are registered through the Operaton Connect SPI (`META-INF/services/org.operaton.connect.spi.ConnectorProvider`). The host needs the Connect plugin (`operaton-engine-plugin-connect`, which brings `operaton-connect-core`); it is `provided` here, so add it yourself if your application does not have it.

**Spring Boot**:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-s3-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
<dependency>
  <groupId>org.operaton.bpm</groupId>
  <artifactId>operaton-engine-plugin-connect</artifactId>
</dependency>
```

Configure at least one store (see Configuration); the application refuses to start without one.

**Plain engine**:

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-s3</artifactId>
  <version>VERSION</version>
</dependency>
```

```java
S3StoreConfig docs = new S3StoreConfig();
docs.setBucket("documents");
docs.setRegion("eu-central-1");
S3ConnectorPlugin s3 = new S3ConnectorPlugin();
s3.setStores(Map.of("docs", docs));
configuration.getProcessEnginePlugins().add(new ConnectProcessEnginePlugin());
configuration.getProcessEnginePlugins().add(s3);
// after the engine is closed: s3.close();
```

**Shared engine / distribution**: put `operaton-connector-s3-VERSION.jar` and the AWS SDK jars it depends on into the engine's `lib/` and add `org.operaton.extensions.connector.s3.S3ConnectorPlugin` with its `stores` to the process engine plugins in `bpm-platform.xml`.

## Usage

All operations take the optional parameters `store` (name of a configured store; default store when absent) and `bucket` (overrides the store's bucket).

| Connector id | Parameters | Response parameters |
|---|---|---|
| `s3-put` | `key`*, `content`* (file variable, `byte[]`, `String`, `InputStream`), `contentType`, `metadata` (map) | `bucket`, `key`, `etag`, `versionId` |
| `s3-get` | `key`*, `versionId`, `maxSize` (bytes) | `download`, `bucket`, `key`, `contentType`, `contentLength`, `etag`, `versionId`, `lastModified`, `metadata`, `metadataJson` |
| `s3-list` | `prefix`, `maxKeys` (1-1000), `delimiter`, `continuationToken` | `keys`, `keysJson`, `objects`, `objectsJson`, `commonPrefixes`, `commonPrefixesJson`, `count`, `truncated`, `nextContinuationToken` |
| `s3-copy` | `sourceKey`*, `targetKey`*, `sourceBucket`, `targetBucket`, `sourceVersionId`, `move` (delete the source afterwards), `contentType`, `metadata` (both replace the source's) | `sourceBucket`, `sourceKey`, `bucket`, `key`, `etag`, `versionId`, `moved` |
| `s3-delete` | `key`*, `versionId` (deleting a missing key succeeds, as in S3) | `bucket`, `key`, `versionId`, `deleteMarker` |
| `s3-presign` | `key`*, `method` (`GET` default, or `PUT`), `expiresIn` (`PT15M` or seconds; at most 7 days), `contentType` (PUT: signed, the uploader must send it), `fileName` (GET: download name) | `bucket`, `key`, `method`, `url`, `expiresAt`, `signedHeaders` |

\* required. `etag` has the surrounding quotes removed.

```xml
<bpmn:serviceTask id="store" name="Store document">
  <bpmn:extensionElements>
    <operaton:connector>
      <operaton:inputOutput>
        <operaton:inputParameter name="key">incoming/${documentId}/${documentName}</operaton:inputParameter>
        <operaton:inputParameter name="content">${document}</operaton:inputParameter>
        <operaton:inputParameter name="contentType">${documentContentType}</operaton:inputParameter>
        <operaton:outputParameter name="documentKey">${key}</operaton:outputParameter>
      </operaton:inputOutput>
      <operaton:connectorId>s3-put</operaton:connectorId>
    </operaton:connector>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

A file variable reaches the connector as a stream, so its file name and content type are not passed: set `contentType` explicitly.

**Download into a file variable.** A connector output mapping cannot store a typed value directly, so `s3-get` returns a `download` object; pick the form you need:

```xml
<operaton:outputParameter name="document">${download.fileValue}</operaton:outputParameter>   <!-- file variable: name from the key, content type from S3 -->
<operaton:outputParameter name="body">${download.text}</operaton:outputParameter>            <!-- UTF-8 string -->
<operaton:outputParameter name="raw">${download.bytes}</operaton:outputParameter>            <!-- byte[] -->
```

**Lists.** Lists and maps are not storable as variables unless Java serialization is enabled, so `s3-list` also returns `keysJson` and `objectsJson` as JSON text (use Spin, `${S(keysJson)}`, to iterate); `keys` and `objects` are available to expressions.

**Delegates.** `S3PutDelegate`, `S3GetDelegate`, `S3ListDelegate`, `S3CopyDelegate`, `S3DeleteDelegate` and `S3PresignDelegate` (package `org.operaton.extensions.connector.s3.delegate`) take the same parameters from the service task's input parameters and write the responses as process variables `<resultPrefix><Name>` (default prefix `s3`: `s3Etag`, `s3KeysJson`, ...; only text, number and boolean results). Extra parameters: `resultPrefix`; `contentVariable` (put: name of the variable to upload; a file variable keeps its file name and content type); `targetVariable` (get: file variable that receives the object, default `s3Content`). Use `operaton:class="org.operaton.extensions.connector.s3.delegate.S3PutDelegate"` or, with the starter, `operaton:delegateExpression="${s3PutDelegate}"`. Declare the parameters as input parameters; without `operaton:inputOutput` the delegate would read all variables of the scope.

**Errors.** Every failure is an exception that reaches the engine (job retries and incident, or the caller of a synchronous start): `S3ObjectNotFoundException`, `S3ObjectTooLargeException`, `S3ConnectorException` (unknown store, missing parameter, S3 error with bucket, key and error code). Nothing is turned into an empty result.

**Java API.** `S3StoreRegistry` / `S3Store` expose the same operations without BPMN.

## Configuration

Stores are configured by name; a call selects one with `store`. Properties of the starter are under `operaton.extensions.connector.s3.` and map one to one onto `S3ConnectorPlugin` / `S3StoreConfig` setters.

| Property | Default | Meaning |
|---|---|---|
| `enabled` (starter) | `true` | Switches the auto-configuration off |
| `default-store` | the only store | Store used when a call names none |
| `stores.<name>.endpoint` | AWS | Endpoint override for S3-compatible services, e.g. `http://localhost:9000` |
| `stores.<name>.public-endpoint` | `endpoint` | Endpoint written into presigned URLs when clients reach the store under another address |
| `stores.<name>.region` | `us-east-1` with an endpoint, else the AWS region chain | AWS region |
| `stores.<name>.bucket` | none | Default bucket; a call may override it |
| `stores.<name>.path-style-access` | `true` with an endpoint, else `false` | Path-style addressing (MinIO and most others need it) |
| `stores.<name>.access-key`, `secret-key`, `session-token` | none | Static credentials (set `access-key` and `secret-key` together) |
| `stores.<name>.profile` | none | Named profile of the shared AWS config/credentials files (not together with `access-key`) |
| `stores.<name>.max-download-bytes` | `10485760` | `s3-get` fails above this size; a call may pass `maxSize` |
| `stores.<name>.presign-expiry` | `PT15M` | Default lifetime of presigned URLs (max 7 days) |
| `stores.<name>.connect-timeout` | `PT10S` | HTTP connect timeout |
| `stores.<name>.socket-timeout` | `PT60S` | HTTP read timeout |

Without `access-key` and `profile`, credentials come from the AWS default provider chain (environment, system properties, shared profile, container/instance credentials, web identity). Credentials are never logged, never part of `toString()`, and never accepted per call. Configuration is validated at startup (application and engine refuse to start); the SDK client of a store is created on first use. Request and response checksums are only calculated when required, for compatibility with older S3-compatible stores.

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |
| 2.0 | yes |

## Example

[`example/`](example/): a document review process. An uploaded document is stored in S3, a reviewer user task gets a presigned download link, approved documents are moved to an `archive/` prefix and rejected ones are deleted. `compose.yaml` starts PostgreSQL and LocalStack (S3 with the `documents` bucket); `./mvnw verify` runs the process end to end with Testcontainers.

## Development

```bash
./mvnw -B -pl connectors/s3 -amd verify                                  # whole extension incl. the example (Docker needed)
./mvnw -B -pl connectors/s3/operaton-connector-s3 -am verify -Doperaton.version=2.1.6   # another Operaton version
```

On Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. Tests: unit tests, engine tests with `ProcessEngineExtension` (H2) and the store tests run against a real S3 implementation (LocalStack 3.8 via Testcontainers).

Design notes and best-guess decisions:

- Fresh implementation; no code is ported. AWS SDK for Java v2 (`S3Client`, `S3Presigner`, Apache HTTP client; the Netty client is excluded).
- The Connect SPI instantiates connectors through `ServiceLoader` without configuration, so the connector finds its stores through the engine that runs the command (`Context.getProcessEngineConfiguration()`, then the registered `S3ConnectorPlugin`, also inside Spring Boot's composite plugin). There is no static state; two engines in one JVM have independent stores.
- Connectors read file variables as streams, which loses file name and content type; delegates can read the typed variable (`contentVariable`).
- `s3-copy` (also used for moves) is an addition to the requested ids; it is what the archive step needs.
- Test and example S3: MinIO images are no longer published on Docker Hub or Quay, so LocalStack 3.8 (no auth token needed) is used instead of MinIO. The code uses only standard S3 operations and path-style access, so MinIO works with the same configuration.
- The SDK client of a store is not closed when the engine closes (the Operaton plugin API has no shutdown hook): call `S3ConnectorPlugin.close()` yourself; the starter does it through the Spring bean lifecycle.
- Known limits: no multipart or streaming transfer, no per-call credentials, no server-side encryption or ACL parameters, no batch delete.

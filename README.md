# Operaton Extensions

Plugins, connectors and external task clients ("extensions") for the
[Operaton](https://operaton.org) BPMN engine. Each extension is a separate module with its own version and release, declares and verifies the
Operaton versions it supports, and ships documentation and a runnable example.

**Is there an extension for …?** See the [catalog](docs/catalog.md).

## Quick start

Java extensions (plugins and connectors): coordinates follow one pattern, groupId
`org.operaton.extensions`:

| Kind | artifactId | Spring Boot starter (optional) |
|---|---|---|
| Plugin | `operaton-plugin-<name>` | `operaton-plugin-<name>-spring-boot-starter` |
| Connector | `operaton-connector-<name>` | `operaton-connector-<name>-spring-boot-starter` |

```xml
<dependency>
  <groupId>org.operaton.extensions</groupId>
  <artifactId>operaton-connector-<name></artifactId>
  <version><!-- see the extension's README --></version>
</dependency>
```

Clients (other languages) are installed from their registry:

| Language | Package |
|---|---|
| JavaScript/TypeScript | npm `@operaton-extensions/external-task-client` |
| Python | PyPI `operaton-external-task-client` |
| Go | `go get github.com/kthoms/operaton-extensions/clients/go` |
| Rust | crates.io `operaton-external-task-client` |

Registration, configuration and the verified Operaton versions are in each extension's README.

## Layout

| Path | Content |
|---|---|
| `plugins/<name>/`, `connectors/<name>/` | one Java extension each: artifact, optional starter, `example/`, docs |
| `clients/<language>/` | one external task client each (`js`, `python`, `go`, `rust`) |
| `<extension>/extension.yml` | manifest of every extension: kind, compatibility, status |
| `parent/` | the shared parent POM |
| `compatibility/` | one POM per supported Operaton minor |
| `docs/` | [catalog](docs/catalog.md) and [conventions](docs/conventions/README.md) |
| `tools/` | extension template and `new-extension.sh` |
| `.github/` | workflows and scripts |

## Build

Requires JDK 21 (bytecode targets Java 17) and Docker (integration tests). Clients are not in the
Maven reactor; build them with their own toolchain ([clients.md](docs/conventions/clients.md)).

```bash
./mvnw -B verify                              # everything
./mvnw -B -pl connectors/<name> -amd verify   # one extension
./mvnw spotless:apply                         # fix formatting
```

Building against another Operaton version:
[docs/conventions/compatibility.md](docs/conventions/compatibility.md).

## Documentation

- [Conventions](docs/conventions/README.md), starting with the
  [repository design](docs/conventions/repository-design.md)
- [Contributing](CONTRIBUTING.md) and [contributing an extension](docs/conventions/contributing-an-extension.md)

## License

[Apache License 2.0](LICENSE). Third-party code is listed in [NOTICE](NOTICE).

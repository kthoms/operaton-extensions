# Module structure

The tree is defined in [repository-design.md](repository-design.md#layout). This document fixes
names and the rules that go with it.

## Naming

`<kind>` is `plugin` or `connector`; `<kinds>` is its plural (`plugins`, `connectors`); `<name>` is
lower-case kebab-case, starting with a letter (`kafka`, `history-cleanup-metrics`).

| Item | Pattern | Example (`connector`, `kafka`) |
|---|---|---|
| Folder | `<kinds>/<name>/` | `connectors/kafka/` |
| groupId (all modules of the extension) | `org.operaton.<kind>.<name>` | `org.operaton.connector.kafka` |
| Aggregator artifactId | `<name>-<kind>-parent` | `kafka-connector-parent` |
| Artifact (released) | `<name>-<kind>` | `kafka-connector` |
| Starter (optional, released) | `<name>-<kind>-spring-boot-starter` | `kafka-connector-spring-boot-starter` |
| Module folder | the artifactId | `connectors/kafka/kafka-connector/` |
| Java package | `org.operaton.extensions.<kind>.<name without dashes>` | `org.operaton.extensions.connector.kafka` |
| Package for `history-cleanup-metrics` | | `org.operaton.extensions.plugin.historycleanupmetrics` |
| Tag | `<kinds>/<name>/v<version>` | `connectors/kafka/v1.3.0` |
| Property prefix | `operaton.extensions.<kind>.<name>.` | `operaton.extensions.connector.kafka.` |

Sub-packages (`...kafka.internal`, `...kafka.config`) are free. Types in a package named
`internal` are not API and may change in any release.

## Clients

A client lives in `clients/<language>/` (`js`, `python`, `go`, `rust`), is not part of the Maven
reactor, and has the manifest `kind: client`. Its native project and package names:

| Language | Project | Published as |
|---|---|---|
| `js` | `package.json`, TypeScript | npm `@kthoms/operaton-external-task-client` |
| `python` | `pyproject.toml` | PyPI `operaton-external-task-client` |
| `go` | `go.mod` | module `github.com/kthoms/operaton-extensions/clients/go` |
| `rust` | `Cargo.toml` | crates.io `operaton-external-task-client` |

Per-language rules: [clients.md](clients.md). Self-containment, `example/` and the README shape
apply as for Java extensions; the version lives in the language's project file (Go: the tag).

## Modules of a Java extension

| Module | Released | Contains |
|---|---|---|
| `<kinds>/<name>/` (aggregator) | no | version, compatibility, metadata, module list |
| `<name>-<kind>` | yes | the plugin or connector, usable with a plain engine |
| `<name>-<kind>-spring-boot-starter` | yes, optional | auto-configuration and property binding only; no logic |
| `example/` | no | runnable Spring Boot application, see [examples.md](examples.md) |

Add a starter only when auto-configuration adds value over registering the plugin by hand. The
starter depends on the artifact; the artifact never depends on Spring Boot.

## Manifest and metadata

Every extension, of every kind, has an `extension.yml` at its root with `kind`, `name`, `title`,
`description`, `build`, `compatibility`, `since` and `status` (schema and meaning:
[repository-design.md](repository-design.md#the-manifest-extensionyml)). The catalog, CI and
release scripts read it. The aggregator POM keeps only the version, `<name>` and `<description>`,
which mirror the manifest. The description is one short sentence starting with a verb
("Publishes process events to Kafka").

## Self-containment

An extension depends on Operaton, its own third-party libraries and the parent POM; never on
another extension. Shared code is copied, not shared. The same holds for `example/`: it depends
on the extension's own artifact and nothing else from this repository.

## What is released

Released: the artifact modules (artifact and optional starter), flattened, with sources and
Javadoc jars; for a client, its package on its registry. Not released: the root and parent POMs, aggregators, `compatibility/`,
`example/`, `docs/`, `tools/`. These carry `maven.deploy.skip` where they are Maven modules.
`CHANGELOG.md` is generated at release time and never edited by hand.

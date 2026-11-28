# Repository design

This document is the single source of truth for how the repository is organised, built, verified
and released. Every other convention document and every workflow implements it. Change it first,
then the implementation.

## Goals

- A growing catalog of **extensions** for [Operaton](https://operaton.org) in three kinds:
  **plugins** (engine-side extensions, Java), **connectors** (integrations with external systems
  used from BPMN, Java) and **clients** (external task clients for other languages: JS/TS, Python,
  Go, Rust, talking to the engine's REST API).
- "Is there an extension for …?" should, over time, be answered with yes: see
  [the catalog](../catalog.md).
- Every extension has **its own lifecycle**: its own version, its own releases, its own release
  branches. Releases are frequent and touch only what changed.
- Every extension **declares and verifies** the Operaton versions it supports.
- All modules look the same: same layout, same build, same documentation shape.

## Layout

```
pom.xml                          root aggregator (never released)
parent/pom.xml                   operaton-extensions-parent: the only shared build file (never released)
plugins/pom.xml                  aggregator of all plugins
plugins/<name>/                  one plugin
connectors/pom.xml               aggregator of all connectors
connectors/<name>/               one connector
clients/<language>/              one external task client (not part of the Maven reactor)
compatibility/operaton-<minor>/  one tiny POM per supported Operaton minor (Dependabot keeps the patch current)
docs/catalog.md                  the extension catalog
docs/conventions/                central rules (this file, structure, code, docs, licensing, release)
tools/extension-template/        the skeleton a new extension is created from
tools/new-extension.sh           creates a new extension from the template
.github/                         workflows, scripts, labeler, Dependabot, JReleaser
```

### The manifest: `extension.yml`

Every extension, whatever its kind and language, has an `extension.yml` at its root. It is the
single source of truth the catalog, CI and release scripts read:

```yaml
kind: connector            # plugin | connector | client
name: kafka                # the folder name, lower-case kebab-case
title: Kafka connector
description: Produce to and consume from Apache Kafka in BPMN processes.
build: maven               # maven | npm | python | go | cargo
compatibility: [2.0, 2.1, 2.2]   # Operaton minors, each verified in CI
since: 2.2                 # first Operaton minor it was built for
status: incubating         # incubating | stable | deprecated
```

The version is not in the manifest: it lives where the build tool keeps it (`pom.xml`,
`package.json`, `pyproject.toml`, `Cargo.toml`; Go has none, its version is the tag).

### One Java extension (plugin or connector)

Every Java extension has the same shape. `<kind>` is `plugin` or `connector`, `<name>` is lower-case
kebab-case (`kafka`, `history-cleanup-metrics`).

```
<kinds>/<name>/
  extension.yml                            the manifest
  pom.xml                                  extension aggregator (groupId org.operaton.<kind>.<name>, artifactId <name>-<kind>-parent): version
  README.md                                user documentation (required sections: conventions/documentation.md)
  CHANGELOG.md                             generated at release time; do not edit by hand
  NOTICE                                   only when code was ported from another project (attribution)
  docs/                                    further user and developer documentation, as needed
  <name>-<kind>/                           the released artifact (jar)
    pom.xml
    src/main/java/org/operaton/extensions/<kind>/<name-without-dashes>/
    src/main/resources/
    src/test/java/...                      unit and engine tests (embedded engine, H2)
  <name>-<kind>-spring-boot-starter/       optional: Spring Boot auto-configuration
  example/                                 a runnable, near-real-world Spring Boot application (never released)
    pom.xml, compose.yaml, README.md, src/main/..., src/test/... (Testcontainers ITs)
```

Rules:

- **Self-contained.** An extension depends on Operaton, on its own third-party libraries and on
  the parent POM, never on another extension in this repository.
- The extension aggregator's parent is `parent/pom.xml`; its modules inherit from the aggregator.
  The aggregator carries the extension's **version**; all its modules share it.
- Released artifacts are **flattened** (`flatten-maven-plugin`, `oss` mode): the published POM has
  no parent, so the parent POM is never released.
- `example/` and the aggregator itself are never deployed (`maven.deploy.skip`).

### One client (external task client)

```
clients/<language>/                 language: js | python | go | rust
  extension.yml                     kind: client, build: npm | python | go | cargo
  README.md, CHANGELOG.md, NOTICE (when ported), docs/
  <the language's native project>   package.json + src/ (TypeScript, published as @kthoms/operaton-external-task-client),
                                    pyproject.toml + src/ (PyPI operaton-external-task-client),
                                    go.mod (module github.com/kthoms/operaton-extensions/clients/go),
                                    Cargo.toml + src/ (crates.io operaton-external-task-client)
  tests/ or the language's test folder   unit tests + integration tests against a real engine
  example/                          a near-real-world worker with compose.yaml starting operaton/operaton
```

A client follows the conventions of its language (formatter, linter, test runner: see
`conventions/clients.md`), the same documentation shape as every extension, and the same
feature set: fetch-and-lock with long polling, complete, handle failure (retries, retry timeout),
handle BPMN error, extend lock, unlock, typed variables (incl. JSON), topic subscriptions with
variable filters, business key / process definition filters, basic and bearer authentication,
configurable max tasks, back-off on errors, graceful shutdown.

The extension aggregator's `<name>` and `<description>` mirror the manifest.

## Build

- Maven, through the wrapper (`./mvnw`). Java 17 bytecode (`maven.compiler.release=17`), built
  and tested on JDK 21 in CI.
- `operaton.version` (parent POM) is the Operaton version an extension builds against by
  default: the latest patch of the newest supported minor. Every extension imports
  `org.operaton.bpm:operaton-bom:${operaton.version}`.
- Build one extension: `./mvnw -B -pl <kinds>/<name> -amd verify` (the aggregator and its modules).
- Build one extension against another Operaton version:
  `./mvnw -B -pl <kinds>/<name>/<name>-<kind> -am verify -Doperaton.version=2.1.6`.
- Formatting: Spotless (Palantir Java Format) checks on `verify`; `./mvnw spotless:apply` fixes.
- `.github/scripts/check-structure.sh` refuses an extension whose manifest or layout deviates.

## Compatibility verification

- `compatibility/operaton-<minor>/pom.xml` pins the **latest patch** of one Operaton minor
  (`x.y.0` included as soon as it exists). Dependabot updates these POMs with **patch updates
  only**, so each one always names the newest patch of its minor.
- A weekly workflow (`compatibility-new-minor.yml`) checks Maven Central and opens a PR adding the
  folder for a new minor. Adding a minor to an extension's manifest `compatibility` is a
  deliberate change in that extension's PR.
- CI builds a matrix per changed extension: one entry per minor in its manifest's
  `compatibility`, each at the version pinned in `compatibility/operaton-<minor>/pom.xml`. The script
  `.github/scripts/compat-matrix.sh` produces it. Java extensions run their tests with
  `-Doperaton.version=<v>`; clients run their integration tests against the Docker image
  `operaton/operaton:<v>` (env `OPERATON_VERSION`, `OPERATON_URL`).
- A minor that fails is either fixed or removed from the manifest's `compatibility` in the same PR. A
  version that is not verified is never claimed.

## Branches

| Branch | Purpose |
|---|---|
| `develop` | integration; every feature PR targets it; the default branch |
| `main` | releases: merging `develop` into `main` releases every extension changed since its last release |
| `feature/*` | one piece of work |
| `release/<kinds>/<name>/<major>.<minor>.x` | patch line of one released extension minor, e.g. `release/connectors/kafka/1.3.x` |

## CI (`build.yml`)

- Runs on pull requests to `develop`, `main` and `release/**`, and on pushes to them.
- `.github/scripts/changed-extensions.sh <base> <head>` lists the extensions whose tree changed.
  A change to `parent/`, `compatibility/`, the root `pom.xml` or the build workflow and its
  scripts selects **all** extensions.
- One job per (extension, Operaton minor): unit and engine tests of the extension's artifact
  modules. One job per changed extension at the default version: the full extension including
  `example/` (Testcontainers ITs, Docker available on the runner) and the structure check
  (`.github/scripts/check-structure.sh`).
- A final `build-result` job is the single required check.

## Release

Tags: `<kinds>/<name>/v<version>` (e.g. `connectors/kafka/v1.3.0`, `clients/go/v1.0.0` — which
is also the tag form Go requires for a module in a subfolder). Versions follow SemVer; a
`develop` POM always holds the **next minor** as `-SNAPSHOT`, a release branch the next patch.

### Minor releases from `main` (`release.yml`, on push to `main`)

1. Detect the extensions whose tree changed since their last tag (an extension without a tag is
   new and is released).
2. For each, in a matrix, call the reusable `release-extension.yml`:
   1. set the release version (the `-SNAPSHOT` dropped), build, test, deploy the artifact modules
      into `<extension>/target/staging-deploy`;
   2. write the changelog (`git log` of the extension's folder since its previous tag, Conventional
      Commits grouped) to `CHANGELOG.md` and as the JReleaser external changelog;
   3. commit the release version on a detached HEAD, tag it, run JReleaser (`full-release`: GitHub
      release + Maven Central deploy, signed). Clients publish with their registry's own tooling
      instead of the Maven Central deploy (npm with provenance, PyPI trusted publishing,
      `cargo publish`; Go needs only the tag), and JReleaser creates only the GitHub release;
   4. create `release/<kinds>/<name>/<major>.<minor>.x` from the tag and set it to the next patch
      `-SNAPSHOT`.
3. A final job commits, on `develop`, each released extension's next minor `-SNAPSHOT` and the
   Dependabot entry for each new release branch (patch updates only, that folder only).

### Patch releases (`release-patches.yml`, monthly and on demand)

For every `release/**` branch with commits since its extension's last tag, call
`release-extension.yml` for that branch: release the patch version, tag, and set the branch to the
next patch `-SNAPSHOT`.

### Secrets

`GPG_PUBLIC_KEY`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `MAVENCENTRAL_USERNAME`,
`MAVENCENTRAL_PASSWORD`; `NPM_TOKEN`, `CARGO_REGISTRY_TOKEN`; PyPI uses trusted publishing (no
secret). Without them a release runs as a dry run. Every release workflow has a
`dry_run` input.

## Dependency updates (Dependabot)

- `develop`: Maven updates (minor and patch) for `parent/`, every Java extension and its example;
  npm, pip, gomod and cargo updates for each client; GitHub Actions updates.
- `compatibility/*`: patch updates only (keeps each minor on its latest patch).
- Each `release/**` branch: one entry, patch updates only, for its extension folder, added by the
  release workflow.
- `auto-merge.yml` merges Dependabot PRs and agent PRs that match `.github/auto-merge.config.yml`
  once every check is green and no review thread is open.

## Workflow security

All workflows pin actions by commit SHA, use least-privilege `permissions`, never check out PR
heads in `pull_request_target`, and pass `${{ }}` values through `env:`; `zizmor-check.yml` audits
every workflow change.

# Documentation

## Extension README

Every extension has a `README.md` with exactly these level-2 headings, in this order. A CI script
(`.github/scripts/check-structure.sh`) enforces them. Add more content as level-3 headings under
these; do not add other level-2 headings.

```markdown
# <Extension name>

One sentence: what it does (same as the POM description).

## When to use
## Installation
## Usage
## Configuration
## Compatibility
## Example
## Development
```

| Section | Content |
|---|---|
| When to use | The problem it solves, when to choose it, and when not (alternatives, limits). |
| Installation | Maven coordinates for each way of using it, see below. |
| Usage | How to enable it and use it from a process model; minimal BPMN/Java snippets. |
| Configuration | Every property: name, type, default, meaning, in a table. Plugin properties and, if there is a starter, `operaton.extensions.<kind>.<name>.*`. |
| Compatibility | The table of verified Operaton minors, see below. |
| Example | What `example/` demonstrates and how to run it (`docker compose up -d`, `./mvnw spring-boot:run`, `./mvnw verify`). |
| Development | How to build and test this extension, how to run against another Operaton version, design notes, and the sources of ported code (see [licensing-and-attribution.md](licensing-and-attribution.md)). |

### Installation

Give all applicable variants, with the version as `<version>`:

For clients, give the registry install command instead (`npm install`, `pip install`,
`go get`, `cargo add`).

Variants for Java extensions:

- **Plain engine**: the artifact dependency, and how to register the plugin
  (`processEnginePlugins` in the engine configuration).
- **Spring Boot**: the starter dependency (if there is one) and the properties to set.
- **Shared engine / distribution**: where to put the jar (for example `lib/`) and the
  `bpm-platform.xml` or configuration entry.

```xml
<dependency>
  <groupId>org.operaton.connector.kafka</groupId>
  <artifactId>kafka-connector</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Compatibility

The table is generated from the `compatibility` list in `extension.yml`, never written from
memory. Format:

| Operaton | Verified |
|---|---|
| 2.2 | yes |
| 2.1 | yes |

Only minors listed in `compatibility` appear. How they are verified:
[compatibility.md](compatibility.md).

## Further documents

Put them in the extension's `docs/` folder and link them from the README:

| File | Content |
|---|---|
| `docs/user-guide.md` | Longer usage material, recipes, troubleshooting |
| `docs/developer-notes.md` | Internals, extension points, how to debug |
| `docs/design-decisions.md` | Decisions with context and consequences, newest first |

## Catalog entry rule

Every extension has exactly one row in [the catalog](../catalog.md). The tables are **generated** from
`extension.yml` (`title`, `description`, `compatibility`, `status`): run `tools/generate-catalog.py` in the
pull request that adds an extension or changes one of those fields, and commit the result. CI runs
`tools/generate-catalog.py --check` and fails when the catalog is out of date. Status is `incubating`,
`stable` or `deprecated`. The backlog sections are written by hand; remove an idea when it is built.

## Style

Plain, precise English; no marketing wording. Tables where they help. State each rule once and link
to it. Link with relative paths.

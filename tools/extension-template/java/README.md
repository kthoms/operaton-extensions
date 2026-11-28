# __TITLE__

TODO one paragraph: what it does and for whom.

## When to use

TODO: the problems it solves; when NOT to use it and what to use instead.

## Installation

Maven (embedded engine or Spring Boot):

```xml
<dependency>
  <groupId>org.operaton.__KIND__.__NAME__</groupId>
  <artifactId>__NAME__-__KIND__</artifactId>
  <version>VERSION</version>
</dependency>
```

TODO: registration (Spring Boot auto-configuration / process engine plugin / shared engine).

## Usage

TODO: the BPMN and/or Java snippets for the main use cases.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `operaton.extensions.__KIND__.__NAME__.…` | | TODO |

## Compatibility

Verified in CI against these Operaton versions (from `extension.yml`):

| Operaton | Verified with |
|---|---|
| 2.0 | 2.0.x (latest patch) |
| 2.1 | 2.1.x (latest patch) |
| 2.2 | 2.2.x (latest patch) |

## Example

[`example/`](example/): TODO one line describing the scenario.

## Development

```bash
./mvnw -B -pl __KINDS__/__NAME__ -amd verify
```

TODO: design notes, sources and attribution (if any code is ported), known limitations.

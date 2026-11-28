# Licensing and attribution

This repository is licensed under the [Apache License 2.0](../../LICENSE). Every contribution is
made under it.

## What may be reused

| Source license | Source code | As a binary dependency |
|---|---|---|
| Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause | yes, with attribution | yes |
| EPL-2.0 | no | yes |
| Other permissive licenses | ask in the PR first | ask in the PR first |

Never copy, port or translate:

- GPL, AGPL or LGPL source;
- SSPL or other source-available licenses;
- Camunda Enterprise or other commercial code;
- n8n source (Sustainable Use License): its ideas and behaviour may inspire a design, its code may
  not be used.

Dependencies must be compatible with Apache-2.0 distribution: no GPL/AGPL/LGPL (or SSPL, BUSL)
dependency in released artifacts. When unsure, do not add it; ask.

## Ports and direct copies

A direct copy or port of code from another project:

1. keeps the original copyright and license header in the file;
2. adds under it the line `Ported from <url> (<license>)`;
3. is listed in the extension's `NOTICE` (created for the first port) and in the root
   [NOTICE](../../NOTICE) under "Third-party code": project, URL, license, files;
4. is recorded in the extension README's `## Development` section.

Reimplementing an idea from scratch needs none of this, but name the inspiration in the README
when it is substantial.

## Review

The PR description states the source and license of everything not written for this repository.
Reviewers block a PR that lacks this. Contributors and agents: if the origin or license of a
snippet is unknown, do not include it.

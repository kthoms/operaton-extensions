---
name: new-extension
description: Create and complete a new Operaton extension (plugin, connector or external task client) end to end — scaffold from the template, implement, test against every declared Operaton minor, document, add the example and the catalog entry, and open the PR to develop. Use when asked to "build an extension for X", "add a connector/plugin/client", or when picking an item from the catalog backlog.
---

# new-extension

Builds one extension so that it is indistinguishable in shape from every other one. The rules
live in `docs/conventions/` — read `repository-design.md`, `module-structure.md`,
`documentation.md`, `examples.md`, `compatibility.md`, `licensing-and-attribution.md` and, for a
client, `clients.md` before writing code. This skill is the order of work, not a second copy of
the rules.

## Inputs

- kind: `plugin` | `connector` | `client`
- name: kebab-case (for a client: `js`, `python`, `go`, `rust`)
- a one-sentence purpose (from the user, the issue, or the catalog backlog row)

## Steps

1. **Check it does not exist.** `docs/catalog.md`, `ls plugins connectors clients`, and Operaton
   core (`connect/`, `engine-plugins/`, `spring-boot-starter/` in operaton/operaton). An existing
   equivalent → stop and report it instead.
2. **Branch.** A worktree on `feature/<kind>-<name>` from `origin/develop` (worktree skill, or
   `git worktree add --no-track -b feature/<kind>-<name> ../operaton-extensions-worktrees/feature-<kind>-<name> origin/develop`).
3. **Sources and licenses.** For every project you take code or design from: its license
   (`gh api repos/<owner>/<repo> -q .license.spdx_id`). Port only Apache-2.0/MIT/BSD code; keep
   the original header, add "Ported from <url> (<license>)", add the NOTICE entries (extension
   and root). Ideas from n8n or Camunda 8 connectors: reimplement, copy nothing.
4. **Scaffold.** `tools/new-extension.sh <kind> <name> "<Title>"`. Fill `extension.yml`
   (description, compatibility — start with the minors you will actually verify).
5. **Design in best guess.** Decide the API (BPMN usage: connector id / delegate / external task
   topic; configuration properties `operaton.extensions.<kind>.<name>.*`; Spring Boot
   auto-configuration when the extension is used from Spring Boot). Write the decisions and the
   guesses you made into the README's Development section and the PR body — do not stop to ask.
6. **Implement** in the artifact module. Operaton dependencies are `provided`. Fail loudly
   (CLAUDE.md #1 rule). No static mutable state; thread-safe under the job executor.
7. **Test.** Engine tests with `ProcessEngineExtension` (H2) for the engine-facing behaviour;
   Testcontainers for the external system (toolchains you do not have locally run from Docker images:
   `golang:1.x`, `rust:1`). Run against every declared minor (2.x only; 1.x is out of scope):
   `./mvnw -B -pl <kinds>/<name>/operaton-<kind>-<name> -am verify -Doperaton.version=<pin>`
   with each pin from `compatibility/operaton-<minor>/pom.xml`. A minor that cannot be made to
   work is removed from `compatibility` (never claimed). Clients: unit tests plus integration
   tests against `operaton/operaton:<pin>`.
8. **Example.** A near-real-world scenario in `example/` (BPMN, compose.yaml, Testcontainers IT
   running the process end to end), per `docs/conventions/examples.md`.
9. **Document.** README with the seven required sections; more under `docs/` when needed.
   `.github/scripts/check-structure.sh <kinds>/<name>` must pass.
10. **Catalog.** Run `tools/generate-catalog.py` (the catalog tables are generated from `extension.yml`) and remove the idea from the backlog in `docs/catalog.md`.
11. **Verify the whole extension once:** `./mvnw -B -pl <kinds>/<name> -amd verify` (Docker
    needed for the example; on Colima set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`).
12. **Review, commit, PR.** Run the code review (review-code skill) and fix what it finds;
    commit with Conventional Commits (`feat(<kind>-<name>): …`); push; open a PR to `develop`
    with the summary, the verified minors, the sources/licenses, the best-guess decisions and
    the test evidence. Never merge to `main`, tag or release.

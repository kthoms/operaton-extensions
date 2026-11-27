# Detection table — what changed, or what the plan names → reviewers

Read by `review-code` Step 2 (against a changed-file list) and by `review-plan` Step 3 (against the topics, components, and paths a plan names). **One table, two readers, one file** — it lives here, in `review-code/references/`; review-plan reads it by relative path and declares review-code a required companion for that reason. `bootstrap-agents` writes it from its roster survey.

Rows are `signal → reviewer agent(s)`. The left column is anything decidable from a file list OR from plan text: a path glob, a directory, a component or subsystem name, a topic keyword. Multiple rows may match; every match becomes a lens, and review-code Step 2 (`bin/reviewer-fold.sh`) decides how many agents carry them: matched lenses are folded into fewer agents under `MAX_REVIEWERS`, never dropped.

## This repository (written by bootstrap-agents at onboarding step 2)

| If the change touches (paths in a diff, or topics a plan names)… | Add reviewer |
|---|---|
| `plugins/**`; process engine plugins, BPMN parse listeners, execution/task listeners, engine configuration | `plugin-engineer` |
| `connectors/**`; connectors, external-task workers, third-party integrations | `connector-engineer` + `security-reviewer` |
| `clients/**`; external task clients (JS/TS, Python, Go, Rust), REST protocol, client packaging, cross-language parity | `client-engineer` + `security-reviewer` |
| `pom.xml`, `parent/pom.xml`, `compatibility/**`, `.github/workflows/release*.yml`, `.github/jreleaser/**` (critical paths) — OR `**/src/test/**`, `clients/*/test*/**`, `**/extension.yml`; Operaton compatibility matrix | `test-engineer` |
| `.github/**`, `pom.xml`, `parent/pom.xml`, `compatibility/**`, `**/extension.yml`, `tools/**`; CI, Dependabot, JReleaser, release branches, change-tree builds | `release-engineer` |
| `.github/workflows/**`; workflow permissions, secrets, supply chain | `security-reviewer` |
| `docs/**`, `**/README.md`, `**/example/**`; extension documentation, catalog, conventions | `docs-writer` |
| `**/NOTICE*`, `**/LICENSE*`, `plugins/*/pom.xml`, `connectors/*/pom.xml`, `clients/*/package.json`, `clients/*/pyproject.toml`, `clients/*/go.mod`, `clients/*/Cargo.toml`; code adopted from Camunda 7, CIB seven, n8n or another project; new dependencies | `license-reviewer` |

The `code-reviewer` agent has no row: it is the general reviewer the skill runs when nothing else matches.

## Rules

- **Always-on reviewer:** the zero-context reviewer is added by the skill in every mode, not by this table, and is not counted when the skill decides between isolated and collaborative review.
- **Overlapping rows are intended.** A handler file can match the API, infra, performance, and observability rows; each is a different lens and each is kept. More lenses than `MAX_REVIEWERS` allows fold into shared agents (each agent carries every profile folded into it); don't dedupe the matches, and don't narrow a row only to save agents — the cap does that. A large or high-stakes diff is the case for raising `MAX_REVIEWERS` for that run: the roughly 2× blocking-finding yield in Design Principle 4 came from an uncapped run of about ten reviewers, and each agent added is worth its tokens only when its expected findings are.
- **The test reviewer row is load-bearing.** It must match every `CRITICAL_GLOBS` path, because that reviewer writes and runs the real E2E tests review-code Step 10 gates on. A `CRITICAL_GLOBS` entry with no reviewer row behind it is a gate that can never pass.
- **Zero domain matches is a valid outcome** (a docs-only or scripts-only diff). The skill then runs one general reviewer, isolated. Do not add a catch-all row to avoid that case.
- **A reviewer named here must exist** as an agent definition your harness can spawn. `bootstrap-agents` verifies this; if you edit by hand, check it.

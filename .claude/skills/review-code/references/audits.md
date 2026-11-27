# Step 6 audits — main-thread, same turn as reviewer dispatch

Each audit is a **trigger** (decided from the changed-file list) and a **procedure** you run yourself with Read/Grep, synchronously, in the turn that dispatches the reviewers. They are deliberately not delegated: they catch what reviewers who focus on the diff window systematically miss, and they must be done before synthesis.

Every CRITICAL raised here needs a `VERIFIED:` line (file:line + snippet, docs URL + section, or script output), exactly like a reviewer's.

---

## Built-in 1 — Test quality (ALWAYS RUNS)

**1a — Inline stubs.** For each changed test file: does it import the function under test from source, or define its own copy inline? A test that tests its own arithmetic passes forever regardless of the real code. → WARNING with file:line.

**1b — Silent ignore (schema ↔ runtime).** For each new or changed field in any schema or type in the diff: find where runtime code reads it. Defined but never read → CRITICAL ("users can configure this and it does nothing"). Also the reverse: runtime stopped reading a field the schema still accepts. High-risk shapes: filter/condition/rule fields; cadence/interval/proration fields; new optional API body fields (does the service pass them through, does storage persist them); new migration columns no query selects.

**1c — Adversarial coverage, three rounds.**
- Round 1: categorize every test case in changed test files as HAPPY PATH or ADVERSARIAL (error, invalid input, auth rejection, empty/null/zero, partial failure, concurrency, boundary, malformed, duplicate, timeout, rollback). Over 80% happy-path → WARNING naming the missing categories.
- Round 2: for each changed non-test file, list the adversarial cases that SHOULD exist and don't — inputs that break assumptions, dependencies failing mid-operation, what a malicious tenant could do, two requests racing, exactly-at-threshold boundaries. Each → WARNING.
- Round 3: combinations and cascades — concurrent + partial failure + retry; step 2 failing after step 1 committed; cache stale after a failed write. SUGGESTION unless it involves data corruption or financial accuracy, then WARNING.

---

## Built-in 2 — Real E2E coverage (ALWAYS RUNS; feeds Step 10)

**Trigger:** any changed file matching `CRITICAL_GLOBS`. No match → record the exemption category and reason, done.

For each matching file: identify what it exposes (a route and its methods, a service function, a handler). Search your E2E directories for a spec that exercises it at a **real boundary** — real HTTP through the real handler, a real browser, a real datastore. Then:
- No real spec → CRITICAL.
- Spec covers only the happy path → CRITICAL (needs auth rejection, validation failure, error responses, read-back after mutation).
- A file under the E2E directory that mocks the datastore or imports the handler in-process → CRITICAL ("integration test mislabeled as E2E"). Mechanical check: grep E2E dirs for your mocking framework's mock calls and for direct client imports paired with a mock.
- Spec exists but has not been EXECUTED in-session against this tree with green output → CRITICAL. Existence is not execution.

Print a table, one line per file, then hand the result to Step 10.

---

## Your audits — extension point

### Extension contract (this repository)

**Trigger:** the diff adds or changes files under `plugins/<name>/` or `connectors/<name>/`.

Source: CLAUDE.md "Project Rules" and the domain invariant.

1. **Declared versions = verified versions.** Read the module's declared Operaton minor versions and the CI matrix entries that build it: every declared minor (x.y.0 included) has an entry at its latest patch, and no entry is missing a declaration. Mismatch → CRITICAL.
2. **Self-contained.** The module's POM has the repository parent and no dependency on another module of this repository. Violation → CRITICAL.
3. **Docs and example.** The module has its documentation (when to use, how to install, how to use) and an example that builds; config keys and versions in the docs match the code and POM. Missing or contradicting → CRITICAL.
4. **Provenance.** Files carrying another project's copyright or package names have an origin header and a NOTICE entry with a compatible license. Missing → CRITICAL.
5. **Homogeneity.** Layout and naming follow `docs/conventions/`. Deviation → WARNING.

### Test-helper payload validation (connectors)

**Trigger:** the diff adds a reusable test helper (under `connectors/**/src/test/**`) that calls a real API or a stub server of one.

Grep existing passing tests for at least one call to each API method the helper uses and compare payload shapes structurally (field names, types, single vs batch). Drift → CRITICAL. No working precedent for a method → the helper is untested surface, not tested surface; CRITICAL until executed end-to-end. (A review converged with zero dissent on a helper whose payload matched nothing real; it failed most of its scenarios at runtime.)

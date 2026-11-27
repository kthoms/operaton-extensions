# Step 4 — the lead's structural audits

Run on the main thread, in the same turn as reviewer dispatch, on every plan. Each prints its block even when it finds nothing: a silent absence is indistinguishable from an audit that never ran. Every CRITICAL carries a `VERIFIED:` line (quoted plan passage, file:line, docs section, or script output).

---

## Built-in 1 — Deferred-items audit

Scan the whole plan for language that pushes work out of it: "future PR", "follow-up", "separate task/issue", "out of scope", "later", "TODO/FIXME/TBD", "phase N+1", "post-launch", "nice to have", "tests can be added", "testing is deferred", "split into", "stacked PR", "keep #N open", "validate later", "checklist comment".

Classify every hit:
- **CRITICAL** — deferred tests, error handling, data validation, financial logic, migrations; or any part of the originating issue pushed to another PR without a blocker `WORK_UNIT_RULE` allows. The plan is revised to include it, or the user explicitly approves the deferral with a stated reason.
- **WARNING** — deferred docs, minor polish, observability. Surface for awareness.
- **OK** — an explicit out-of-scope with a reason ("key rotation is out of scope — needs org-admin access").

```
DEFERRED ITEMS AUDIT:
- [CRITICAL] Phase 3 defers the E2E scenario to a follow-up PR — must be in this plan
- [WARNING] "Metrics dashboard is TBD" — surfaced for awareness
- (none found)
```

Any CRITICAL → the plan is revised before implementation; do not proceed past it without user approval.

---

## Built-in 2 — Work-unit and close-out audit

Read the plan's `## PRs` section (its `PRs: <N>` line and the additional-PR table) and its `## Close-out` section. The start skill's plan template writes both, and its `check-plan-sections.sh` refuses a plan without them. A plan from elsewhere that lacks one → **WARNING**, assume one PR, and say so.

- More PRs than `WORK_UNIT_RULE` allows and an additional PR has no allowed blocker → **CRITICAL**. Fold it in; phases are commits, not PRs. "Easier to review", "smaller diff", "phase 2" are not blockers.
- Additional PR with a blocker but no user approval → **WARNING** until approved; it is the first item under "Decisions needed", as `⚠️ ADDITIONAL PR REQUIRED (N total): <scope> — owns #<N> — blocker: <blocker>`.
- An originating issue the close-out does not close with `ISSUE_CLOSE_KEYWORD #N` → **CRITICAL**, unless a declared additional PR owns it (then this PR says `Refs #N`).
- A post-merge obligation homed somewhere your process doesn't track (an issue checklist, PR-body prose, "at release" with no ledger) → **CRITICAL**. The homes are: do it in-session with a bounded wait, or register it where your release process reads obligations.
- Open sub-issues of an originating parent not folded in or declared → **CRITICAL**.

```
WORK-UNIT & CLOSE-OUT AUDIT:
- PRs: 1  (or N — each additional PR with blocker + approval status)
- [CRITICAL] #42 is the originating issue but the close-out has only `Refs #42`
- [WARNING] No PR section — assuming PRs: 1
- (none found)
```

---

## Built-in 3 — Requirement traceability

The one audit that can shrink a plan. Map each phase or step to the specific element it serves: a line of the plan's own root-cause section, a sentence of the issue, a named requirement. Run the exclusion list over every row **before** reporting.

```
REQUIREMENT TRACEABILITY AUDIT:
| Phase / step | Requirement or root-cause element it serves | Mapped? |
|---|---|---|
| Phase 1 | Root cause ¶2 — config dropped at flush | yes |
| Phase 3 | (none found) | UNMAPPED |
- Source of truth used: <plan root-cause section / issue #N body / requirement doc — name it>
- Excluded-category rows: <phase → class guard | tests | error handling | observability | tenancy | prior decision | declared fold-in, or "none">
```

- **UNMAPPED → WARNING**, never CRITICAL. Nothing is broken; it is a scope question for the user. It goes in the Simplification options block as an option with its size.
- **Excluded rows are dropped, not reported.** A phase that exists for a class-level guard or sibling sweep, tests, error handling, observability, tenancy or auth, a settled prior decision, or a fold-in the plan *declares* serves your engineering rules rather than the ticket. Flagging those would put this audit in conflict with the rules that mandate them.
- An **undeclared** out-of-scope phase is not a sanctioned fold-in; UNMAPPED fires normally.

All rows mapping is the normal outcome. Emit the table anyway.

---

## Built-in 4 — Test-plan audit

1. **Unit/integration tests** for new service functions, routes, utilities → missing on substantial new logic: WARNING. Unit tests alone never satisfy items 3–5.
2. **Test baseline** — run existing tests first when the area has them → missing: WARNING.
3. **Real E2E through real boundaries** for anything touching `CRITICAL_GLOBS`: endpoint called with real bodies, status/shape/values asserted; auth (valid, invalid, missing, wrong tenant); validation (missing fields, wrong types, oversized input); service functions exercised through the API layer; mutations read back and asserted. Missing, or happy-path only → **CRITICAL**.
4. **Adversarial cases, named, three rounds** — attacks (injection, auth bypass, cross-tenant access, malformed or oversized input, negative or duplicate submissions), edge cases (zero, empty, null, exactly-at-threshold, first/last, races), failure modes (timeouts, partial failure mid-transaction, rollback consistency, retries causing double-writes). Round 1 obvious; round 2 adversarial (≥3 new); round 3 combinations and cascades (≥2 new). "Add edge-case tests" with nothing named → **CRITICAL**.
5. **Close-out criterion** — an explicit step: *build real E2E for <scope> AND execute in-session against the implemented code; not closed until green output is captured.* "Will run in CI" is post-merge diagnostics, not a gate. Exempt categories must state the exemption: docs-only, type-only refactor, infra-topology-only, internal script with no production reach. Missing on a non-exempt plan → **CRITICAL**.
6. **UI flows** — if the plan touches pages, forms, or components and has no browser-level E2E step in the body (not bolted on at the end) → **CRITICAL**.

```
TEST PLAN AUDIT:
- Unit tests: WARNING — new service functions in Step 2 have no test step
- Baseline: OK — Step 1 runs existing tests
- Real E2E: MISSING — plan adds 3 routes, only unit tests with mocks [CRITICAL]
- Adversarial (3 rounds): MISSING — no named cases [CRITICAL]
- Close-out criterion: MISSING [CRITICAL]
- UI E2E: N/A — no UI changes
```

---

## Parallel execution map

Which plan steps touch disjoint files or modules and can run concurrently; which depend on which; where a step could be split to widen the parallel front. For the implementer, who may hand independent steps to separate agents or worktrees.

```
PARALLEL EXECUTION MAP:
- Steps 1, 2, 3 → parallel (independent modules)
- Step 4 → after 1 + 2
- Steps 5, 6 → parallel after 4
```

---

## Your audits — extension point

### Extension completeness (this repository)

**Trigger:** the plan adds or changes an extension under `plugins/`, `connectors/` or `clients/`.

Source: CLAUDE.md "Project Rules" (layout, lifecycle, compatibility, sources, autonomy).

1. **Compatibility matrix** — does the plan declare the Operaton minor versions the extension supports and add a CI matrix entry per minor (x.y.0 included, at its latest patch) with tests on a real embedded engine (clients: integration tests against `operaton/operaton:<version>`)? Missing → CRITICAL (the domain invariant).
2. **Self-contained module** — does the extension depend on nothing in the repository but the parent POM (clients: on nothing at all), and follow the module layout in `docs/conventions/`? A shared helper module or cross-extension dependency → CRITICAL.
3. **Documentation and example** — does the plan include the module's docs (when to use, how to install, how to use) and a near-real-world example that builds in CI, plus the catalog entry? Missing → CRITICAL.
4. **Provenance** — when the plan adopts code from Camunda 7 community extensions, CIB seven, n8n or another project: is the source license named and compatible, and is attribution (file headers, NOTICE) a plan step? Missing → CRITICAL; an incompatible license → BLOCK (re-implement from scratch instead).
5. **Release scope** — does the plan avoid bumping released versions of other extensions and touching release branches? A change that forces every extension to release (parent POM) is called out → WARNING.

```
EXTENSION COMPLETENESS:
- Compatibility matrix: OK — 1.0, 1.1 declared, matrix entries in plan step 4
- Self-contained: OK
- Docs and example: MISSING — no example step [CRITICAL]
- Provenance: OK — re-implemented, no copied code
- Release scope: OK
```

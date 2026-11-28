---
name: extension-health
description: Maintenance sweep over all extensions — open Dependabot PRs, failing compatibility checks, release branches with unreleased fixes, stale extensions, documentation drift and catalog accuracy — with a report and the fixes it can make. Use for "check the health of the extensions", weekly maintenance, or before a release.
---

# extension-health

Keeps a growing number of extensions current and active, each on its own lifecycle.

## Checks (read-only first, parallel where independent)

1. **Dependabot:** `gh pr list --author app/dependabot --json number,title,baseRefName,statusCheckRollup`
   — failing ones get a fix PR or a note; green ones are left to auto-merge.
2. **Compatibility:** every minor in `compatibility/` vs. each extension's `compatibility`; the
   newest Operaton minor on Maven Central vs. the newest pin (→ `add-operaton-minor`).
3. **CI on develop:** `gh run list --branch develop --workflow build.yml --limit 5`.
4. **Release branches:** `git ls-remote --heads origin 'release/*'` — commits since the
   extension's last tag (they are released by the monthly workflow; report how many wait).
5. **Unreleased work on develop:** extensions changed since their last tag (`.github/scripts/release-info.sh`).
6. **Structure and docs:** `.github/scripts/check-structure.sh` for every extension; README
   compatibility table equals `extension.yml`; catalog row present and accurate.
7. **Staleness:** extensions without a commit in 6 months, `status: deprecated` still in the
   reactor, open issues per extension.

## Output

A table per extension (compatibility, last release, pending changes, open PRs, problems), then
the fixes: small ones are made in a PR to `develop`; large ones are proposed as issues (filed
after the user approves).

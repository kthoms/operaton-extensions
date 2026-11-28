# HumanMaxxing follow-ups

Proposed during the agent onboarding (HumanMaxxing 0.1.1). Each one is switched on or done in its own PR; none is a gate yet.


1. ~~**Set up CI**~~ (done with the project scaffold: `.github/workflows/build.yml`) — the repository has no CI yet. Exact change: add a GitHub Actions workflow running the Maven build and tests once there is code. Evidence: no `.github/` folder, no files at all. Needs first: code and a build file.
2. **Install a secret scanner** — no secret-scan tool is installed on this machine (`gitleaks`, `trufflehog` not found). Exact change: `brew install gitleaks`, then `SECRET_SCAN_CMD="gitleaks git --no-banner"` in checkpoint and ship. Evidence: `command -v gitleaks` → not found.
3. **Install shellcheck** — for the hook and skill scripts the install adds. Exact change: `brew install shellcheck`. Evidence: `command -v shellcheck` → not found.
4. **Re-run bootstrap-agents in maintenance mode once modules exist** — the roster (8 agents) was generated for the planned layout, with planned Maven commands (`./mvnw -B -pl plugins/*`, `./mvnw -B -pl connectors/*`). Needs first: a pom.xml and the first extension modules.
5. **Fill the check slots** in checkpoint (`.claude/skills/checkpoint/SKILL.md` values block) and ship (prose slots, `ship.env` CHECK_TASKS): TEST_CMD/BUILD_CMD as the scoped Maven command, LINT_CMD/LINT_CI_CMD once a check-only formatter exists (e.g. spotless:check), each added to the skill's allowed-tools. Evidence: not tried, no pom.xml at install.
6. **Switch ship to offload and post-merge CI** — `ship.env`: DEFAULT_MODE=offload, POSTMERGE_CI=required, REQUIRED_CHECKS_CMD, once the build workflow and branch protection on develop exist.
7. **UNIT_TESTS_IN_REVIEW** — `none` in review-code and ship; set `local` (or `ci`) once tests exist.
8. **Turn on the end-to-end gate** for `pom.xml`, `.github/workflows/release*.yml`, `.github/jreleaser/**`: `sed -i.bak 's/^# off: //' .claude/critical-paths.txt`, and set checkpoint's CRITICAL_GLOBS_FILE=.claude/critical-paths.txt with TEST_GLOBS=**/src/test/**.
9. **Switch the guards on** — `bash ~/humanmaxxing/hooks/bin/hooks-install.sh plan --hooks protected-file-guard,commit-check,pending-question-reprint,review-nudge --settings .claude/settings.json --mode enforce`, then apply with the printed hash; first set COMMIT_CHECK_CMD in `.claude/hooks/hooks.env` (a secret scan once gitleaks is installed). Live check: in the first session after the merge, `/hooks` lists the 4 handlers.
10. **Second opinion** — the second-opinion plugin is installed at project scope; filling SECOND_OPINION_CMD in review-code and review-plan sends diffs and plans to another vendor (Codex or Antigravity) and needs the user's consent and that vendor's CLI.
11. **Protected files** — the list is empty; candidates once code exists: the file declaring each extension's supported Operaton versions, the root parent POM's shared version properties.
12. ~~**Branch naming**~~ (resolved: both `feature/<slug>` and `<issue>-<slug>` are feature branches) — the worktree skill names issue branches `<number>-<slug>`, while CLAUDE.md says `feature/*`; align one of them.
13. ~~**LICENSE file**~~ (done with the project scaffold: Apache-2.0) — the license-reviewer assumes Apache-2.0; the repository has no LICENSE yet.

---
name: tutorial
description: A short, read-only walkthrough of what this repository has installed - the main flow (start, review-plan, build, review-code, ship, retro) and the side tools, which hooks warn and which enforce, ship's review gate, the open onboarding follow-ups, and one concrete next step. Changes nothing.
user-invocable: true
allowed-tools: Bash(bash .claude/skills/tutorial/bin/tutorial-data.sh *), Read, Glob, Grep
---

# /tutorial — how to use what's installed

Run it in a new session after the onboarding (or any time later) to see how the installed pieces fit together and
what to do next. **It is read-only:** it reads files and prints a walkthrough. It never edits a file, commits, files
an issue, switches a hook on, or runs another skill. Nothing to configure.

---

## Design principles

1. **Read, never change.** Its only command is the data script below, which reads files and runs the installed
   hooks check (itself read-only). Anything the walkthrough suggests is for the user to run.
2. **Only what is installed.** The walkthrough names the skills this repository has; a missing one gets one line on
   how to add it, never instructions for a command that does not exist here.
3. **A missing part is a line, not a stop.** A missing hooks file, ship config or follow-ups file is said in one
   line, and the walkthrough carries on with the next part.
4. **Written for a person.** Short, scannable, plain words; no keyword lines, no raw script output. No question
   unless there is a real choice to make (there usually is none).

---

## Step 1 — Read the state

From the repository's top folder (the session's starting folder), run once:

```bash
bash .claude/skills/tutorial/bin/tutorial-data.sh --repo .
```

It always exits 0 inside a repository and prints one fact per line (its header lists them): `SKILL`, `HOOKS-MODE`,
`HOOK`, `HOOKS-CHECK-LINE`, `REVIEW-GATE`, `TRACKER`, `ISSUE-CMD`, `FOLLOWUPS`, `FOLLOWUP` and
`FOLLOWUPS-OTHER-ITEMS`, ending with `END`. If the
command cannot run (exit 2: not a repository; or the script is missing), say so in one line and give the walkthrough
from Step 2's text alone, with every part marked "could not read".

## Step 2 — Write the walkthrough

Four short parts, in this order, each a heading and a few lines. Use only what the data says.

**1. The main flow.** One line each, in order, for each skill the data lists as `installed`:

| Skill | When to use it |
|---|---|
| `/start <issue>` | Beginning a piece of work: reads the issue, checks its claims against the code, and writes a plan (in its own worktree folder). With no tracker, `/start "describe the task"` |
| `/review-plan` | Before writing code: several reviewers check the plan |
| build | You and the agent implement the plan (no command) |
| `/review-code` | When the change is done: reviewers, a fix pass, and the sentinel `ship` checks |
| `/ship` | Commit, pull request, CI and merge in one pass; done only when merged with green CI |
| `/retro` | Once a week: a read-only look back at the week against your own rules |

Then the side tools, the same way:

| Skill | When to use it |
|---|---|
| `/checkpoint` | Save mid-work: docs, quality checks and a commit, no pull request |
| `/trim` | Before you `/compact` a long conversation: writes a compact command that keeps what matters |
| `/peer-review` | Someone else's pull request: a verdict with `file:line` evidence, read-only unless you ask |
| `/worktree` | Parallel work: one folder per branch, so two tasks never share a checkout |

A skill the data lists as `missing` is left out of its table; list those names in one line at the end of this part:
"Not installed here: `<names>`. To add one, re-run the onboarding (`getting-started.claude.md` from your HumanMaxxing
download) and ask for it, or copy `skills/<name>/` from the download into `.claude/skills/<name>/` and fill its
Configuration block." `SKILL-OTHER` lines are the repository's own skills: name them in one line ("Also here:"),
without describing them.

**2. This install.**
- Hooks. `HOOKS-MODE warn`: "The protected-file guard, the commit check and the review nudge are in **warn mode**:
  they print what they would have blocked and never block. Switching them on is one of your follow-ups." `enforce`:
  "They are **on**: the guard asks before a change to a protected file, the commit check refuses a commit while its
  check fails, and the review nudge holds a stop once when a change has no review." `invalid`: the value is not warn
  or enforce, so the hooks treat it as enforce; say so. Name only the hooks the data lists as installed; the
  pending-question reprint is always on (it has no mode). `COMMIT-CHECK-CMD empty`: the commit check has nothing to
  run yet. `HOOKS-ENV missing` or `malformed`, or no hook installed: "No hooks are set up here (`.claude/hooks/hooks.env`
  missing)", and go on.
- The hooks check (`HOOKS-CHECK-LINE`): its result in one line ("hooks check: passed" or "hooks check: failed, <the
  reason in plain words>"), and any `FAIL` line in plain words. Skip its `NOTE` lines already said above.
  `HOOKS-CHECK missing`: say the check is not installed and go on.
- Review gate. `REVIEW-GATE sentinel`: "`/ship` merges only after a `/review-code` run recorded for this exact
  change." `manual`: "`/ship` asks you to confirm the change was reviewed." Any other value: name it as written.
  `missing` or `unset`: "`ship` is not configured here (no `.claude/skills/ship/ship.env`)", and go on.

**3. Your follow-ups.** The onboarding's proposed follow-ups (checks to switch on, gates, guards).
- `FOLLOWUPS file=<path> open=<n>` with n above 0: "N open in `<path>`", then the open titles as a short numbered
  list (at most 8, then "and N more"), and "**Next:** <the first title>" (the file lists them in the order to do
  them; a finished item is deleted from the file). Read the file only when a title alone does not say what the item
  is, and then only that item's section.
- `open=0` with a file and no `FOLLOWUPS-OTHER-ITEMS` line: "No open follow-ups."
- `open=0` with `FOLLOWUPS-OTHER-ITEMS <n>`: the file is in an older shape, so never say there are none: "`<path>`
  has items this walkthrough cannot read as a list; open it to see what is left." Then go on.
- `file=missing`: with `TRACKER github`, "No follow-ups file: the onboarding filed them as issues in your tracker, or
  there were none." Otherwise: "No follow-ups file found (`humanmaxxing-follow-ups.md`): nothing was written, or it
  was moved." Then go on.

**4. Your next step.** Exactly one concrete suggestion, the last line of the walkthrough:
- With `/start` installed and `ISSUE-CMD set` (it reads issues from your tracker): "Pick an issue and run
  `/start <number>`."
- With `/start` installed and `ISSUE-CMD empty` (no tracker): "Pick a task and run `/start \"describe the task\"`."
- With `/start` installed and `ISSUE-CMD unknown`: "Pick an issue and run `/start <number>` (no tracker: `/start
  \"describe the task\"`)."
- Without `/start`: "Pick a task, build it, then run `/review-code` and `/ship`" using only the skills installed, or,
  with none of them, "Add `start` (see part 1) and run `/start` on your next task."

Keep the whole walkthrough on one screen where it fits: no preamble, no recap at the end, no offer of more help.

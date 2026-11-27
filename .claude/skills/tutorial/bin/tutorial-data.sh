#!/usr/bin/env bash
# tutorial-data.sh — the mechanical half of the tutorial skill: reads what this repository has installed and prints it
# as stable lines. It READS ONLY: it never writes, fetches, commits or calls the network. It runs the installed
# hooks-check.sh (itself read-only) when there is one. The wording for the person is the skill's job.
#
#   tutorial-data.sh [--repo <dir>]        (default: the current folder)
#
# Conventions:
#   - bash 3.2 clean; `set -u`; no `set -e`. ALWAYS exits 0 once it found the repository: a missing part is a line
#     saying so, never a failure, so one missing file never stops the walkthrough. Exit 2 only when <dir> is not
#     inside a git repository.
#   - Configuration files (hooks.env, ship.env, retro.env) are parsed, never sourced: KEY=value lines and # comments
#     on their own lines.
#   - Output: one fact per line, keyword first, free text (a path, a title, a raw line) LAST.
#       ROOT <path>
#       SKILL <name> installed|missing                        the main flow and side tools, in walkthrough order
#       SKILL-OTHER <name>                                    any other skill installed under .claude/skills/
#       HOOKS-ENV present|missing|malformed
#       HOOKS-MODE warn|enforce|invalid|unknown <raw value>   one key (HOOKS_MODE) for the guard, the commit check and
#                                                             the review nudge; absent or empty = enforce
#       HOOK <name> installed|missing mode=warn|enforce|always|n/a
#       COMMIT-CHECK-CMD set|empty|n/a
#       HOOKS-CHECK present|missing  /  HOOKS-CHECK-LINE <line>   (the check's NOTE/FAIL lines and its result line)
#       REVIEW-GATE <value>|missing|unset
#       TRACKER <github|none|...>|unset                       from retro.env (the onboarding writes it at step 9)
#       ISSUE-CMD set|empty|unknown                           start's ISSUE_CMD value (empty: start takes a task in words)
#       FOLLOWUPS-OTHER-ITEMS <n>                             with open=0: item-like lines in another shape (other
#                                                             heading levels, list items: a file from before the format)
#       FOLLOWUPS file=<path>|missing open=<n>
#       FOLLOWUP <n> <title>                                  each open item, in file order (the first is next)
#       END

set -u
REPO="."
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --repo=*) REPO="${1#--repo=}"; shift ;;
    *) echo "usage: tutorial-data.sh [--repo <dir>]" >&2; exit 2 ;;
  esac
done
TOP="$(cd "$REPO" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null)" || TOP=""
[ -n "$TOP" ] || { echo "NOT-A-REPO $REPO"; exit 2; }
echo "ROOT $TOP"

kv_get() {   # kv_get <file> <key> — 0 found (value printed, may be empty) · 1 absent · 2 unreadable or malformed
  local f="$1" want="$2" line k v found=1 val=""
  [ -r "$f" ] || return 2
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in *$'\r'*) return 2 ;; ''|'#'*|*=*) ;; *) return 2 ;; esac
    case "$line" in ''|'#'*) continue ;; esac
    k="${line%%=*}"; v="${line#*=}"
    case "$k" in ''|*[!A-Za-z0-9_]*) return 2 ;; esac
    if [ "$k" = "$want" ]; then val="$v"; found=0; fi
  done < "$f"
  [ "$found" -eq 0 ] || return 1
  printf '%s\n' "$val"
}

# ---- skills ----------------------------------------------------------------------------------------------------------
SK="$TOP/.claude/skills"
FLOW="start review-plan review-code ship retro checkpoint trim peer-review worktree"
for s in $FLOW; do
  if [ -f "$SK/$s/SKILL.md" ]; then echo "SKILL $s installed"; else echo "SKILL $s missing"; fi
done
if [ -d "$SK" ]; then
  for d in "$SK"/*/; do
    [ -f "${d}SKILL.md" ] || continue
    n="$(basename "$d")"
    case " $FLOW tutorial " in *" $n "*) ;; *) echo "SKILL-OTHER $n" ;; esac
  done
fi

# ---- hooks -----------------------------------------------------------------------------------------------------------
HD="$TOP/.claude/hooks"; ENV="$HD/hooks.env"; MODE=enforce
if [ ! -f "$ENV" ]; then
  echo "HOOKS-ENV missing"; MODE=unknown; echo "HOOKS-MODE unknown"
else
  kv_get "$ENV" HOOKS_MODE >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 2 ]; then
    echo "HOOKS-ENV malformed"; MODE=unknown; echo "HOOKS-MODE unknown"
  else
    echo "HOOKS-ENV present"
    raw="$(kv_get "$ENV" HOOKS_MODE 2>/dev/null)" || raw=""
    case "$raw" in
      ''|enforce) MODE=enforce; echo "HOOKS-MODE enforce $raw" ;;
      warn) MODE=warn; echo "HOOKS-MODE warn $raw" ;;
      *) MODE=enforce; echo "HOOKS-MODE invalid $raw" ;;   # the hooks treat any other value as enforce
    esac
  fi
fi
for h in protected-file-guard commit-check review-nudge pending-question-reprint; do
  if [ -f "$HD/$h.sh" ]; then
    case "$h" in pending-question-reprint) m=always ;; *) m="$MODE"; [ "$m" = unknown ] && m=n/a ;; esac
    echo "HOOK $h installed mode=$m"
  else
    echo "HOOK $h missing mode=n/a"
  fi
done
if [ -f "$HD/commit-check.sh" ] && [ -f "$ENV" ]; then
  c="$(kv_get "$ENV" COMMIT_CHECK_CMD 2>/dev/null)" || c=""
  if [ -n "$c" ]; then echo "COMMIT-CHECK-CMD set"; else echo "COMMIT-CHECK-CMD empty"; fi
else
  echo "COMMIT-CHECK-CMD n/a"
fi
if [ -f "$HD/hooks-check.sh" ]; then
  echo "HOOKS-CHECK present"
  HC_OUT="$(cd "$TOP" && bash "$HD/hooks-check.sh" 2>&1 </dev/null)"
  printf '%s\n' "$HC_OUT" | grep -E '^(NOTE|FAIL): |^HOOKS-RESULT ' | sed 's/^/HOOKS-CHECK-LINE /'
else
  echo "HOOKS-CHECK missing"
fi

# ---- ship's review gate ----------------------------------------------------------------------------------------------
SE="$SK/ship/ship.env"
if [ ! -f "$SE" ]; then echo "REVIEW-GATE missing"
else
  g="$(kv_get "$SE" REVIEW_GATE 2>/dev/null)" || g=""
  if [ -n "$g" ]; then echo "REVIEW-GATE $g"; else echo "REVIEW-GATE unset"; fi
fi

# ---- tracker ---------------------------------------------------------------------------------------------------------
RE="$SK/retro/retro.env"
t=""; [ -f "$RE" ] && { t="$(kv_get "$RE" TRACKER 2>/dev/null)" || t=""; }
if [ -n "$t" ]; then echo "TRACKER $t"; else echo "TRACKER unset"; fi
# start's ISSUE_CMD decides /start's form (a number, or a task in words). The onboarding writes it in start's
# `Values for this repo` block as a `KEY=value` line; the slot definitions above it are `ISSUE_CMD  — …` lines.
STS="$SK/start/SKILL.md"
if [ -f "$STS" ] && grep -q '^ISSUE_CMD=' "$STS"; then
  ic="$(sed -n 's/^ISSUE_CMD=//p' "$STS" | tail -1 | sed 's/[[:space:]]*$//')"
  case "$ic" in ''|UNVERIFIED) echo "ISSUE-CMD empty" ;; *) echo "ISSUE-CMD set" ;; esac
else
  echo "ISSUE-CMD unknown"
fi

# ---- follow-ups ------------------------------------------------------------------------------------------------------
# The onboarding writes <docs root>/humanmaxxing-follow-ups.md (step 11): one `## <title>` section per item; a finished
# item is deleted from the file. The docs root differs per repository, so the file is found by name.
FU="$(cd "$TOP" && { git ls-files -- '*humanmaxxing-follow-ups.md' 'humanmaxxing-follow-ups.md'; \
      git ls-files --others --exclude-standard -- '*humanmaxxing-follow-ups.md' 'humanmaxxing-follow-ups.md'; } 2>/dev/null \
      | LC_ALL=C sort -u | head -1)"
if [ -z "$FU" ] || [ ! -f "$TOP/$FU" ]; then
  echo "FOLLOWUPS file=missing open=0"
else
  TITLES="$(sed -n 's/^##[[:space:]][[:space:]]*//p' "$TOP/$FU" | sed 's/[[:space:]]*$//')"
  n=0; [ -n "$TITLES" ] && n="$(printf '%s\n' "$TITLES" | grep -c .)"
  echo "FOLLOWUPS file=$FU open=$n"
  if [ "$n" -eq 0 ]; then   # an older file with items in another shape is not a confident zero
    o="$(grep -E '^(#{2,6}[[:space:]]|[-*][[:space:]]|[0-9][0-9]*\.[[:space:]])' "$TOP/$FU" | grep -c .)"
    [ "$o" -gt 0 ] && echo "FOLLOWUPS-OTHER-ITEMS $o"
  fi
  i=0
  printf '%s\n' "$TITLES" | while IFS= read -r ti; do
    [ -n "$ti" ] || continue
    i=$((i + 1)); echo "FOLLOWUP $i $ti"
  done
fi
echo "END"
exit 0

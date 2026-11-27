#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: changelog.sh <ext-path> <previous-tag-or-empty> <version> [<release-notes-file>]
#
# Writes the changelog of one extension release from the Conventional Commits that touch
# <ext-path> since <previous-tag> (all history when empty), up to HEAD:
#   - prepends a "## [<version>] (<date>)" section to <ext-path>/CHANGELOG.md (replacing an
#     existing section of the same version, so a re-run is idempotent);
#   - writes the same entries, without the version heading, to <release-notes-file>
#     (default <ext-path>/target/jreleaser/changelog.md): the JReleaser external changelog.
#
# Commits are grouped by type (feat, fix, perf, refactor, docs, deps, test, build/ci, other);
# breaking changes ("type!:" or a "BREAKING CHANGE:" footer) are also listed first. Merge
# commits and the release workflow's own "chore(release): ..." commits are left out.
#
# GITHUB_SERVER_URL and GITHUB_REPOSITORY (set in Actions) form the commit links; locally they
# default to https://github.com and kthoms/operaton-extensions.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 3 ] || [ $# -eq 4 ] || die "usage: $0 <ext-path> <previous-tag-or-empty> <version> [<release-notes-file>]"
EXT="${1%/}"
PREV="$2"
VERSION="$3"
cd_repo_root
NOTES="${4:-$EXT/target/jreleaser/changelog.md}"

[ -d "$EXT" ] || die "$EXT is not a directory"
if [ -n "$PREV" ]; then
  git rev-parse --verify --quiet "$PREV^{commit}" >/dev/null || die "previous tag '$PREV' not found"
  RANGE="$PREV..HEAD"
else
  RANGE="HEAD"
fi

REPO_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-kthoms/operaton-extensions}"
TAG="$EXT/v$VERSION"
DATE="$(date -u +%Y-%m-%d)"

mkdir -p "$(dirname "$NOTES")"
# Records: hash US subject US body RS
git log --no-merges --format='%H%x1f%s%x1f%b%x1e' "$RANGE" -- "$EXT" |
  python3 -c '
import re, sys
from pathlib import Path

ext, version, prev, tag, repo_url, date, notes_file = sys.argv[1:8]
GROUPS = [
    ("feat", "Features"),
    ("fix", "Bug Fixes"),
    ("perf", "Performance"),
    ("refactor", "Refactoring"),
    ("docs", "Documentation"),
    ("deps", "Dependencies"),
    ("test", "Tests"),
    ("build", "Build"),
    ("other", "Other Changes"),
]
CC = re.compile(r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<desc>.+)$")
entries = {key: [] for key, _ in GROUPS}
breaking = []

for record in sys.stdin.read().split("\x1e"):
    record = record.strip("\n")
    if not record:
        continue
    sha, subject, body = (record.split("\x1f") + ["", ""])[:3]
    subject = subject.strip()
    m = CC.match(subject)
    if m:
        ctype, scope, desc = m.group("type").lower(), m.group("scope"), m.group("desc").strip()
        if ctype == "chore" and scope == "release":
            continue
        if scope == "deps" or ctype == "deps":
            key = "deps"
        elif ctype in ("feat", "fix", "perf", "refactor", "docs", "test"):
            key = ctype
        elif ctype in ("build", "ci"):
            key = "build"
        else:
            key = "other"
        is_breaking = bool(m.group("bang")) or "BREAKING CHANGE:" in body or "BREAKING-CHANGE:" in body
    else:
        scope, desc = None, subject
        key = "deps" if subject.lower().startswith("bump ") else "other"
        is_breaking = "BREAKING CHANGE:" in body
    text = (f"**{scope}:** " if scope else "") + desc
    line = f"- {text} ([{sha[:7]}]({repo_url}/commit/{sha}))"
    entries[key].append(line)
    if is_breaking:
        breaking.append(line)

body = []
if breaking:
    body += ["### ⚠ Breaking Changes", ""] + breaking + [""]
for key, title in GROUPS:
    if entries[key]:
        body += [f"### {title}", ""] + entries[key] + [""]
if not body:
    body = ["No user-facing changes.", ""]

link = f"{repo_url}/compare/{prev}...{tag}" if prev else f"{repo_url}/tree/{tag}/{ext}"
section = [f"## [{version}]({link}) ({date})", ""] + body

Path(notes_file).write_text("\n".join(body).rstrip("\n") + "\n", encoding="utf-8")

changelog = Path(ext) / "CHANGELOG.md"
header = ["# Changelog", "",
          "All notable changes to this extension. Generated at release time from Conventional Commits; do not edit by hand.", ""]
old = changelog.read_text(encoding="utf-8").splitlines() if changelog.is_file() else header
# split the existing file into the preamble and the release sections
first = next((i for i, l in enumerate(old) if l.startswith("## ")), len(old))
preamble, sections = old[:first], old[first:]
if not preamble:
    preamble = header
# drop an existing section of the same version (re-run)
kept, skip = [], False
for l in sections:
    if l.startswith("## "):
        skip = l.startswith(f"## [{version}]")
    if not skip:
        kept.append(l)
while preamble and preamble[-1] == "":
    preamble.pop()
changelog.write_text("\n".join(preamble + [""] + section + kept).rstrip("\n") + "\n", encoding="utf-8")
print(f"{changelog}: added {version}; release notes in {notes_file}", file=sys.stderr)
' "$EXT" "$VERSION" "$PREV" "$TAG" "$REPO_URL" "$DATE" "$NOTES"

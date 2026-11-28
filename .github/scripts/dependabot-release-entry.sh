#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: dependabot-release-entry.sh <ext-path> <release-branch> [<dependabot.yml>]
#
# Adds the Dependabot entry for one release branch (patch updates only, the extension's folder
# only) to .github/dependabot.yml, between the lines
#   # >>> release branches (managed by release workflow) >>>
#   # <<< release branches <<<
# Does nothing when an entry for that target-branch exists already.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || [ $# -eq 3 ] || die "usage: $0 <ext-path> <release-branch> [<dependabot.yml>]"
EXT="${1%/}"
BRANCH="$2"
cd_repo_root
FILE="${3:-.github/dependabot.yml}"

BEGIN_MARKER='# >>> release branches (managed by release workflow) >>>'
END_MARKER='# <<< release branches <<<'
grep -qF "$BEGIN_MARKER" "$FILE" && grep -qF "$END_MARKER" "$FILE" || die "$FILE: release branch markers not found"

if grep -qF "target-branch: \"$BRANCH\"" "$FILE"; then
  echo "$FILE: entry for $BRANCH exists already" >&2
  exit 0
fi

BUILD="$(manifest_get "$EXT" build)" || die "$EXT: extension.yml has no 'build'"
case "$BUILD" in
  maven) ECOSYSTEM=maven ;;
  npm) ECOSYSTEM=npm ;;
  python) ECOSYSTEM=pip ;;
  go) ECOSYSTEM=gomod ;;
  cargo) ECOSYSTEM=cargo ;;
  *) die "$EXT: unknown build '$BUILD'" ;;
esac
ECOSYSTEM_LABEL="$ECOSYSTEM"

ENTRY="$(cat <<EOF
  - package-ecosystem: "$ECOSYSTEM"
    directory: "/$EXT"
    target-branch: "$BRANCH"
    schedule:
      interval: "weekly"
    cooldown:
      default-days: 7
    labels:
      - "dependencies"
      - "dependencies:$ECOSYSTEM_LABEL"
    ignore:
      - dependency-name: "*"
        update-types: ["version-update:semver-major", "version-update:semver-minor"]
    groups:
      patches:
        patterns:
          - "*"
EOF
)"

TMP="$(mktemp)"
END_MARKER="$END_MARKER" ENTRY="$ENTRY" awk '
  { line = $0; sub(/^[ \t]+/, "", line) }
  line == ENVIRON["END_MARKER"] { print ENVIRON["ENTRY"] }
  { print }
' "$FILE" >"$TMP"
cat "$TMP" >"$FILE"
rm -f "$TMP"
echo "$FILE: added $ECOSYSTEM entry for $BRANCH ($EXT)" >&2

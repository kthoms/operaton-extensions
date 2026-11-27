#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: changed-extensions.sh <base-ref> <head-ref>
#
# Prints a JSON array of the extension paths (<kinds>/<name>) whose tree changed between the
# merge base of <base-ref> and <head-ref>, and <head-ref>; e.g. ["connectors/kafka"].
#
# All extensions (that exist at <head-ref>) are selected when
#   - <base-ref> is empty, all zeros (a new branch) or not known to the repository,
#   - a shared build input changed: parent/, compatibility/, the root pom.xml, the Maven wrapper
#     (mvnw, mvnw.cmd, .mvn/), .github/workflows/build.yml or .github/scripts/.
# A change to plugins/pom.xml or connectors/pom.xml selects every extension of that kind.
# Deleted extensions are not listed. See docs/conventions/repository-design.md#ci-buildyml.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || die "usage: $0 <base-ref> <head-ref>"
BASE="$1"
HEAD="$2"
cd_repo_root

git rev-parse --verify --quiet "$HEAD^{commit}" >/dev/null || die "unknown head ref '$HEAD'"

ALL_EXTENSIONS="$(list_extensions "$HEAD")"

emit() { # prints the given newline-separated list as JSON
  local list
  list="$(printf '%s\n' "$1" | sed '/^$/d' | sort -u)"
  if [ -z "$list" ]; then echo '[]'; else printf '%s\n' "$list" | jq -R . | jq -cs .; fi
}

select_all() {
  echo "Selecting all extensions: $1" >&2
  emit "$ALL_EXTENSIONS"
  exit 0
}

case "$BASE" in
  "" | 0000000000000000000000000000000000000000) select_all "no base revision" ;;
esac
git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || select_all "base '$BASE' is not available"

if MERGE_BASE="$(git merge-base "$BASE" "$HEAD" 2>/dev/null)"; then
  CHANGED="$(git diff --name-only "$MERGE_BASE" "$HEAD")"
else
  CHANGED="$(git diff --name-only "$BASE" "$HEAD")"
fi

SELECTED=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    parent/* | compatibility/* | pom.xml | mvnw | mvnw.cmd | .mvn/* | .github/workflows/build.yml | .github/scripts/*)
      select_all "shared build input '$f' changed" ;;
    plugins/pom.xml | connectors/pom.xml)
      kinds="${f%%/*}"
      SELECTED="$SELECTED
$(printf '%s\n' "$ALL_EXTENSIONS" | grep "^$kinds/" || true)" ;;
    plugins/*/* | connectors/*/* | clients/*/*)
      ext="$(echo "$f" | cut -d/ -f1-2)"
      if printf '%s\n' "$ALL_EXTENSIONS" | grep -qxF "$ext"; then
        SELECTED="$SELECTED
$ext"
      fi ;;
  esac
done <<EOF
$CHANGED
EOF

emit "$SELECTED"

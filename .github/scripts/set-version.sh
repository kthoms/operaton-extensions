#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: set-version.sh <ext-path> <version>
#
# Sets the version of one extension in the file its build tool keeps it in and prints the files
# that changed (for `git add`):
#   maven   all POMs of the extension (aggregator, modules, example), confined to the extension.
#           Not versions:set: run on <ext>/pom.xml it also rewrites parent/pom.xml and the
#           root and kinds aggregators (they are reachable through <relativePath>).
#   npm     package.json (+ package-lock.json)
#   python  pyproject.toml [project].version
#   cargo   Cargo.toml [package].version (+ Cargo.lock)
#   go      nothing: the tag is the version
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || die "usage: $0 <ext-path> <version>"
EXT="${1%/}"
VERSION="$2"
cd_repo_root

BUILD="$(manifest_get "$EXT" build)" || die "$EXT: extension.yml has no 'build'"
case "$BUILD" in
  maven | npm | python | cargo)
    ext_py set-version "$EXT" "$VERSION"
    git ls-files --modified -- "$EXT" | grep -E '(^|/)(pom\.xml|package\.json|package-lock\.json|pyproject\.toml|Cargo\.toml|Cargo\.lock)$' || true ;;
  go) ;;
  *) die "$EXT: unknown build '$BUILD'" ;;
esac
echo "$EXT: version set to ${VERSION:-<tag>} ($BUILD)" >&2

#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: extension-matrix.sh '<json array of extension paths>'
#
# Prints the matrix of the full-extension jobs of build.yml: one entry per extension, e.g.
#   [{"extension":"clients/js","build":"npm","operaton_version":"2.2.2"}]
# operaton_version is the default Operaton version the extension is built against: for Java
# extensions the parent POM's operaton.version, for clients the version pinned for the newest
# minor in the manifest's compatibility (the Docker image their integration tests run against).
# An extension without a readable manifest gets build "unknown"; its job then fails in
# check-structure.sh with the reason.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || die "usage: $0 '<json array of extension paths>'"
cd_repo_root

EXTENSIONS="$(printf '%s' "$1" | jq -r 'if type == "array" then .[] else error("expected a JSON array") end')" \
  || die "argument is not a JSON array of extension paths: $1"
PARENT_VERSION="$(ext_py pom-property parent/pom.xml operaton.version 2>/dev/null || true)"

ENTRIES=""
while IFS= read -r ext; do
  [ -n "$ext" ] || continue
  build="$(manifest_get "$ext" build 2>/dev/null)" || build=unknown
  version="$PARENT_VERSION"
  if [ "$build" != maven ]; then
    newest="$(manifest_get "$ext" compatibility 2>/dev/null | tr -d ' \t' | tr ',' '\n' |
      grep -E '^[0-9]+\.[0-9]+$' | sort -t. -k1,1n -k2,2n | tail -1 || true)"
    version=""
    if [ -n "$newest" ] && [ -f "compatibility/operaton-$newest/pom.xml" ]; then
      version="$(ext_py compat-version "compatibility/operaton-$newest/pom.xml")"
    fi
  fi
  ENTRIES="$ENTRIES$(jq -cn --arg extension "$ext" --arg build "$build" --arg operaton_version "$version" \
    '{extension: $extension, build: $build, operaton_version: $operaton_version}')
"
done <<EOF
$EXTENSIONS
EOF
printf '%s' "$ENTRIES" | jq -cs .

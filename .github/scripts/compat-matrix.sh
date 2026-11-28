#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: compat-matrix.sh '<json array of extension paths>'
#
# Prints the compatibility matrix as a JSON list for `strategy.matrix.include`: one entry per
# (extension, Operaton minor in its extension.yml `compatibility`), e.g.
#   [{"extension":"connectors/kafka","build":"maven","minor":"2.1","operaton_version":"2.1.6",
#     "modules":"connectors/kafka/kafka-connector"}]
# operaton_version is the patch pinned in compatibility/operaton-<minor>/pom.xml. `modules` is
# the Maven -pl list of the artifact modules (empty for clients).
#
# Fails when an extension has no manifest, declares no or a malformed minor, or declares a minor
# without a compatibility/operaton-<minor>/ folder.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || die "usage: $0 '<json array of extension paths>'"
cd_repo_root

EXTENSIONS="$(printf '%s' "$1" | jq -r 'if type == "array" then .[] else error("expected a JSON array") end')" \
  || die "argument is not a JSON array of extension paths: $1"

ENTRIES=""
ERRORS=0
while IFS= read -r ext; do
  [ -n "$ext" ] || continue
  [ -f "$ext/extension.yml" ] || { warn "$ext: no extension.yml"; ERRORS=$((ERRORS + 1)); continue; }
  build="$(manifest_get "$ext" build)" || { warn "$ext: extension.yml has no 'build'"; ERRORS=$((ERRORS + 1)); continue; }
  minors="$(manifest_get "$ext" compatibility)" || minors=""
  if [ -z "$minors" ]; then
    warn "$ext: extension.yml declares no 'compatibility'"
    ERRORS=$((ERRORS + 1))
    continue
  fi
  modules=""
  [ "$build" = maven ] && modules="$(artifact_modules "$ext")"
  IFS=',' read -r -a list <<<"$minors"
  for minor in "${list[@]}"; do
    minor="$(echo "$minor" | tr -d '[:space:]')"
    if ! echo "$minor" | grep -Eq '^[0-9]+\.[0-9]+$'; then
      warn "$ext: compatibility entry '$minor' is not a minor version (x.y)"
      ERRORS=$((ERRORS + 1))
      continue
    fi
    pom="compatibility/operaton-$minor/pom.xml"
    if [ ! -f "$pom" ]; then
      warn "$ext: declares Operaton $minor, but $pom does not exist (add the compatibility folder or remove the minor)"
      ERRORS=$((ERRORS + 1))
      continue
    fi
    version="$(ext_py compat-version "$pom")" || { ERRORS=$((ERRORS + 1)); continue; }
    ENTRIES="$ENTRIES$(jq -cn --arg extension "$ext" --arg build "$build" --arg minor "$minor" \
      --arg operaton_version "$version" --arg modules "$modules" \
      '{extension: $extension, build: $build, minor: $minor, operaton_version: $operaton_version, modules: $modules}')
"
  done
done <<EOF
$EXTENSIONS
EOF

[ "$ERRORS" -eq 0 ] || die "compatibility matrix: $ERRORS error(s), see above"
printf '%s' "$ENTRIES" | jq -cs .

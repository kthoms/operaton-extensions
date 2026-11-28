#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: mvn-retry.sh <maven arguments...>
#
# Runs the Maven wrapper (./mvnw at the repository root). When the build fails because dependencies
# or plugins could not be downloaded (repo.maven.apache.org answers some requests with HTTP 429 when
# many jobs build at once, or drops a connection), it runs the build again, up to three more times
# with a growing pause, and with -U so Maven does not reuse its cached "not found" result. A build
# that fails for any other reason (a failing test, a compile error) is never repeated.
#
# In CI (GITHUB_ACTIONS=true, or MVN_MIRROR_SETTINGS set to a settings file) the odd attempts use
# Google's mirror of Maven Central (.github/maven/settings-ci.xml) and the even ones Central itself:
# the runners share IP addresses that repo.maven.apache.org rate-limits (HTTP 429) for minutes, and
# retrying the same host does not help.
#
# MVN_CMD overrides the command (used by the self-test), MVN_RETRY_PAUSE the pause step in seconds.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MVN="${MVN_CMD:-$ROOT/mvnw}"
PAUSE="${MVN_RETRY_PAUSE:-30}"
MAX_ATTEMPTS=4
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

DOWNLOAD_ERRORS='Could not transfer artifact|Non-resolvable import POM|Failed to read artifact descriptor|Could not resolve dependencies|Could not resolve plugin|Plugin .* or one of its dependencies could not be resolved|HTTP Status: (429|5[0-9][0-9])|Connection reset|Premature end of Content-Length|Read timed out|Connect timed out'

MIRROR_SETTINGS="${MVN_MIRROR_SETTINGS:-}"
if [ -z "$MIRROR_SETTINGS" ] && [ "${GITHUB_ACTIONS:-}" = true ]; then MIRROR_SETTINGS="$ROOT/.github/maven/settings-ci.xml"; fi
[ -f "$MIRROR_SETTINGS" ] || MIRROR_SETTINGS=""

attempt=1
extra=()
while :; do
  settings=()
  if [ -n "$MIRROR_SETTINGS" ] && [ $((attempt % 2)) -eq 1 ]; then settings=(-s "$MIRROR_SETTINGS"); fi
  "$MVN" "${settings[@]+"${settings[@]}"}" "${extra[@]+"${extra[@]}"}" "$@" 2>&1 | tee "$LOG"
  status=${PIPESTATUS[0]}
  [ "$status" -eq 0 ] && exit 0
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ] || ! grep -Eq "$DOWNLOAD_ERRORS" "$LOG"; then
    exit "$status"
  fi
  wait=$((attempt * PAUSE))
  echo "::warning::Maven failed while downloading (attempt $attempt of $MAX_ATTEMPTS); trying again in ${wait}s with -U, using $([ -n "$MIRROR_SETTINGS" ] && [ $(((attempt + 1) % 2)) -eq 1 ] && echo 'the mirror' || echo 'Maven Central')"
  sleep "$wait"
  extra=(-U)
  attempt=$((attempt + 1))
done

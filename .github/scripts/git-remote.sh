#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: GH_TOKEN=... git-remote.sh push|fetch <args>...
#
# Runs `git push origin <args>` or `git fetch origin <args>` with the token in GH_TOKEN passed as
# an HTTP header for this one command. The workflows check out with persist-credentials: false,
# so no credential is left in .git/config while builds run.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -ge 2 ] || die "usage: GH_TOKEN=... $0 push|fetch <args>..."
case "$1" in push | fetch) ;; *) die "only push and fetch are supported, not '$1'" ;; esac
[ -n "${GH_TOKEN:-}" ] || die "GH_TOKEN is not set"
cd_repo_root

AUTH="$(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')"
[ -z "${GITHUB_ACTIONS:-}" ] || echo "::add-mask::$AUTH"
CMD="$1"
shift
git -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $AUTH" "$CMD" origin "$@"

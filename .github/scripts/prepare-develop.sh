#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: prepare-develop.sh <plans-json>
#
# After a minor release: on the checked-out develop, moves every released extension to its next
# minor development version, copies its CHANGELOG.md from the release tag and appends the Dependabot
# entry for its new release branch, then commits that as one commit (nothing is pushed). Used by
# release.yml and, with local tags, by the release check on pull requests to main.
#
# <plans-json> is the `plans` output of the release detection: one object per extension with
# extension, tag, version, release_branch and next_develop_version. An extension whose tag does not
# exist is skipped with a warning. Sets `commit=true` in $GITHUB_OUTPUT when a commit was made.
# Needs git's user.name and user.email.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || die "usage: $0 <plans-json>"
PLANS="$1"
cd_repo_root

PREPARED=""
for row in $(echo "$PLANS" | jq -r '.[] | [.extension, .tag, .version, .release_branch, .next_develop_version] | join("|")'); do
  IFS='|' read -r EXT TAG VERSION RELEASE_BRANCH NEXT <<< "$row"
  if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "::warning::$TAG does not exist: $EXT was not released, develop is left unchanged for it"
    continue
  fi
  if [ ! -f "$EXT/extension.yml" ]; then
    echo "::warning::$EXT no longer exists on develop"
    continue
  fi
  if [ -n "$NEXT" ]; then
    CURRENT="$(.github/scripts/release-info.sh version "$EXT")"
    CURRENT_RELEASE="$(.github/scripts/release-info.sh release-version "$CURRENT")"
    # only move forward: develop may already hold a higher (e.g. major) version
    if [ "$(printf '%s\n%s\n' "$CURRENT_RELEASE" "$VERSION" | sort -V | tail -1)" = "$VERSION" ]; then
      .github/scripts/set-version.sh "$EXT" "$NEXT" >/dev/null
    else
      echo "$EXT: develop holds $CURRENT, newer than $VERSION; version left as is"
    fi
  fi
  if git cat-file -e "$TAG:$EXT/CHANGELOG.md" 2>/dev/null; then
    git checkout -q "$TAG" -- "$EXT/CHANGELOG.md"
  fi
  .github/scripts/dependabot-release-entry.sh "$EXT" "$RELEASE_BRANCH"
  git add -u -- "$EXT" .github/dependabot.yml
  [ ! -f "$EXT/CHANGELOG.md" ] || git add -- "$EXT/CHANGELOG.md"
  PREPARED="$PREPARED $EXT@$VERSION"
done

if git diff --cached --quiet; then
  echo "Nothing to commit on develop"
  exit 0
fi
git commit -q -m "chore(release): prepare next development versions" -m "Released:$PREPARED"
[ -z "${GITHUB_OUTPUT:-}" ] || echo "commit=true" >> "$GITHUB_OUTPUT"
git show --stat HEAD

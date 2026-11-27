#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# The local git steps of an extension release (release-extension.yml). Nothing is pushed here:
# the workflow pushes the tag and the branches only when it is not a dry run.
#
# Usage: release-git.sh <command> [args]
#
#   prepare <ext> <version> <previous-tag-or-empty> <release-notes-file>
#       Detach HEAD, set the release version and write the changelog (CHANGELOG.md and the
#       JReleaser release notes).
#   commit-tag <ext> <version> <tag>
#       Commit the release version ("chore(release): release <ext> <version>") on the detached
#       HEAD and create the annotated tag.
#   bump <ext> <tag> <branch> <start-point> <next-version>
#       Check out <branch> at <start-point> (the tag for a new release branch, origin/<branch> for
#       an existing one), set <next-version>, take CHANGELOG.md from <tag> and commit
#       "chore(release): prepare <ext> <next-version>". Prints nothing to commit for Go clients
#       other than the changelog.
#
# The commit author is github-actions[bot] unless git user.name / user.email are configured.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

cd_repo_root
SCRIPTS=.github/scripts

git config user.name >/dev/null 2>&1 || git config user.name "github-actions[bot]"
git config user.email >/dev/null 2>&1 || git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

stage_extension() { # stage the version files and the changelog of <ext>
  git add -u -- "$1"
  [ ! -f "$1/CHANGELOG.md" ] || git add -- "$1/CHANGELOG.md"
}

case "${1:-}:$#" in
  prepare:5)
    EXT="$2" VERSION="$3" PREV="$4" NOTES="$5"
    git checkout -q --detach
    "$SCRIPTS/set-version.sh" "$EXT" "$VERSION" >/dev/null
    "$SCRIPTS/changelog.sh" "$EXT" "$PREV" "$VERSION" "$NOTES"
    ;;
  commit-tag:4)
    EXT="$2" VERSION="$3" TAG="$4"
    [ -z "$(git symbolic-ref -q HEAD || true)" ] || die "HEAD is not detached; run 'prepare' first"
    stage_extension "$EXT"
    git commit -q -m "chore(release): release $EXT $VERSION"
    git tag -a "$TAG" -m "$EXT $VERSION"
    echo "tagged $TAG at $(git rev-parse --short HEAD)" >&2
    ;;
  bump:6)
    EXT="$2" TAG="$3" BRANCH="$4" START="$5" NEXT="$6"
    git checkout -q -B "$BRANCH" "$START"
    [ -z "$NEXT" ] || "$SCRIPTS/set-version.sh" "$EXT" "$NEXT" >/dev/null
    if git cat-file -e "$TAG:$EXT/CHANGELOG.md" 2>/dev/null; then
      git checkout -q "$TAG" -- "$EXT/CHANGELOG.md"
    fi
    stage_extension "$EXT"
    if git diff --cached --quiet; then
      echo "$BRANCH: nothing to commit for $EXT" >&2
    else
      git commit -q -m "chore(release): prepare $EXT ${NEXT:-after $TAG}"
      echo "$BRANCH: prepared $EXT ${NEXT:-after $TAG}" >&2
    fi
    ;;
  *) die "usage: $0 prepare <ext> <version> <previous-tag> <notes-file> | commit-tag <ext> <version> <tag> | bump <ext> <tag> <branch> <start-point> <next-version>" ;;
esac

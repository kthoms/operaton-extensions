#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Release arithmetic and release detection. See docs/conventions/repository-design.md#release.
#
# Usage: release-info.sh <command> [args]
#
#   version <ext>                       version held by the extension's build file
#                                       (pom.xml, package.json, pyproject.toml, Cargo.toml; empty for go)
#   release-version <version>           drop the development suffix: 1.3.0-SNAPSHOT -> 1.3.0, 1.3.0.dev0 -> 1.3.0
#   next-patch <version>                1.3.0 -> 1.3.1
#   next-minor <version>                1.3.2 -> 1.4.0
#   dev-version <ext> <version>         development form for the extension's build:
#                                       maven/npm/cargo 1.4.0-SNAPSHOT, python 1.4.0.dev0, go (none)
#   tag <ext> <version>                 connectors/kafka/v1.3.0
#   release-branch <ext> <version>      release/connectors/kafka/1.3.x
#   last-tag <ext> [<major.minor>]      highest tag of the extension (of that minor line); empty if none
#   needs-release <ext> [<ref>] [<major.minor>]
#                                       prints true when <ext> at <ref> (default HEAD) differs from its
#                                       last x.y.0 tag (with <major.minor>: the last tag of that line);
#                                       untagged: true. Release version bumps and CHANGELOG.md are
#                                       ignored
#   extensions-to-release [<ref>]       JSON array of the extensions at <ref> that need a release
#   patch-candidates [<remote>]         JSON array of {extension, branch} for every
#                                       <remote>/release/<kinds>/<name>/<x>.<y>.x branch (default
#                                       remote: origin) with changes since the last tag of its line
#   plan <ext> <branch>                 key=value lines (for $GITHUB_OUTPUT) describing the release of
#                                       <ext> from the checked-out <branch>: mode (minor|patch), build,
#                                       version, tag, previous_tag, release_branch,
#                                       next_branch_version, next_develop_version
#
# Go clients have no version file: their release version is computed from the tags (the next
# minor of the last tag on main, the next patch of the line on a release branch, $GO_INITIAL_VERSION
# (default 0.1.0, like the extension template) for the first release).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

GO_INITIAL_VERSION="${GO_INITIAL_VERSION:-0.1.0}"
VERSION_FILES_RE='(^|/)(pom\.xml|package\.json|package-lock\.json|pyproject\.toml|Cargo\.toml|Cargo\.lock)$'

release_version() {
  local v="$1"
  v="${v%-SNAPSHOT}"
  v="$(echo "$v" | sed -E 's/\.dev[0-9]*$//')"
  echo "$v" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "'$1' is not a SemVer version (x.y.z with an optional -SNAPSHOT/.devN suffix)"
  echo "$v"
}

split_version() { # sets MAJOR MINOR PATCH
  local v
  v="$(release_version "$1")"
  MAJOR="${v%%.*}"
  v="${v#*.}"
  MINOR="${v%%.*}"
  PATCH="${v#*.}"
}

next_patch() { split_version "$1"; echo "$MAJOR.$MINOR.$((PATCH + 1))"; }
next_minor() { split_version "$1"; echo "$MAJOR.$((MINOR + 1)).0"; }

dev_version() {
  local ext="$1" v build
  v="$(release_version "$2")"
  build="$(manifest_get "$ext" build)" || die "$ext: extension.yml has no 'build'"
  case "$build" in
    maven | npm | cargo) echo "$v-SNAPSHOT" ;;
    python) echo "$v.dev0" ;;
    go) echo "" ;;
    *) die "$ext: unknown build '$build'" ;;
  esac
}

tag_name() { echo "$1/v$(release_version "$2")"; }
release_branch() { split_version "$2"; echo "release/$1/$MAJOR.$MINOR.x"; }

last_tag() {
  local ext="$1" line="${2:-}" pattern
  if [ -n "$line" ]; then pattern="$ext/v$line.*"; else pattern="$ext/v*"; fi
  git tag -l "$pattern" --sort=-v:refname | grep -E "^$ext/v[0-9]+\.[0-9]+\.[0-9]+$" | head -1 || true
}

# true when anything in <ext> differs between <tag> and <ref>, ignoring CHANGELOG.md and the
# extension's own version in its build files (the release commit and the -SNAPSHOT bumps).
differs_from_tag() {
  local ext="$1" tag="$2" ref="$3" tag_version ref_version f
  tag_version="${tag##*/v}"
  ref_version="$(ext_py version-at "$ext" "$ref" 2>/dev/null)" || ref_version=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    [ "$f" = "$ext/CHANGELOG.md" ] && continue
    if echo "$f" | grep -Eq "$VERSION_FILES_RE"; then
      if git cat-file -e "$tag:$f" 2>/dev/null && git cat-file -e "$ref:$f" 2>/dev/null &&
        [ "$(git show "$tag:$f" | ext_py normalize "$tag_version")" = "$(git show "$ref:$f" | ext_py normalize "$ref_version")" ]; then
        continue
      fi
    fi
    echo "  $f changed since $tag" >&2
    return 0
  done <<EOF
$(git diff --name-only "$tag" "$ref" -- "$ext")
EOF
  return 1
}

needs_release() {
  local ext="$1" ref="${2:-HEAD}" line="${3:-}" tag
  if ! git cat-file -e "$ref:$ext/extension.yml" 2>/dev/null; then
    warn "$ext has no extension.yml at $ref: not released"
    echo false
    return
  fi
  if [ -n "$line" ]; then
    tag="$(last_tag "$ext" "$line")"
  else
    # main: compare with the last minor release (x.y.0), the last tag made from main; patch tags
    # are made on release branches and may contain fixes that reached develop differently
    tag="$(git tag -l "$ext/v*.0" --sort=-v:refname | grep -E "^$ext/v[0-9]+\.[0-9]+\.0$" | head -1 || true)"
    [ -n "$tag" ] || tag="$(last_tag "$ext")"
  fi
  if [ -z "$tag" ]; then
    echo "$ext: no tag${line:+ for $line.x} yet" >&2
    echo true
  elif differs_from_tag "$ext" "$tag" "$ref"; then
    echo true
  else
    echo "$ext: unchanged since $tag" >&2
    echo false
  fi
}

extensions_to_release() {
  local ref="${1:-HEAD}" ext out=""
  for ext in $(list_extensions "$ref"); do
    if [ "$(needs_release "$ext" "$ref")" = true ]; then out="$out $ext"; fi
  done
  # shellcheck disable=SC2086
  json_array $out
}

patch_candidates() {
  local remote="${1:-origin}" ref branch rest ext line entries=""
  for ref in $(git for-each-ref --format='%(refname:short)' "refs/remotes/$remote/release/"); do
    branch="${ref#"$remote"/}"
    rest="${branch#release/}"
    line="${rest##*/}"
    ext="${rest%/*}"
    if ! echo "$line" | grep -Eq '^[0-9]+\.[0-9]+\.x$' || ! echo "$ext" | grep -Eq '^(plugins|connectors|clients)/[^/]+$'; then
      warn "ignoring $branch: not release/<kinds>/<name>/<major>.<minor>.x"
      continue
    fi
    line="${line%.x}"
    if [ "$(needs_release "$ext" "$ref" "$line")" = true ]; then
      entries="$entries$(jq -cn --arg extension "$ext" --arg branch "$branch" '{extension: $extension, branch: $branch}')
"
    fi
  done
  printf '%s' "$entries" | jq -cs .
}

plan() {
  local ext="$1" branch="$2" build mode line="" current version prev tag rbranch next_branch next_develop=""
  [ -f "$ext/extension.yml" ] || die "$ext: extension.yml not found"
  build="$(manifest_get "$ext" build)" || die "$ext: extension.yml has no 'build'"
  case "$branch" in
    release/*)
      mode=patch
      line="${branch##*/}"
      [ "release/$ext/$line" = "$branch" ] && echo "$line" | grep -Eq '^[0-9]+\.[0-9]+\.x$' ||
        die "branch '$branch' is not the release branch of $ext (release/$ext/<major>.<minor>.x)"
      line="${line%.x}" ;;
    *) mode=minor ;;
  esac
  prev="$(last_tag "$ext" "$line")"
  if [ "$build" = go ]; then
    if [ "$mode" = patch ]; then
      [ -n "$prev" ] || die "$ext: no tag for $line.x, cannot compute the next patch"
      version="$(next_patch "${prev##*/v}")"
    elif [ -n "$prev" ]; then
      version="$(next_minor "${prev##*/v}")"
    else
      version="$GO_INITIAL_VERSION"
    fi
  else
    current="$(ext_py version "$ext")" || die "$ext: cannot read the version"
    version="$(release_version "$current")"
    if [ "$mode" = patch ]; then
      split_version "$version"
      [ "$MAJOR.$MINOR" = "$line" ] || die "$ext: version $current on $branch is not in the $line line"
    fi
  fi
  tag="$(tag_name "$ext" "$version")"
  if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null; then
    die "$ext: tag $tag already exists; set the next version on $branch first"
  fi
  if [ "$mode" = patch ]; then rbranch="$branch"; else rbranch="$(release_branch "$ext" "$version")"; fi
  next_branch="$(dev_version "$ext" "$(next_patch "$version")")"
  [ "$mode" = patch ] || next_develop="$(dev_version "$ext" "$(next_minor "$version")")"
  echo "mode=$mode"
  echo "build=$build"
  echo "version=$version"
  echo "tag=$tag"
  echo "previous_tag=$prev"
  echo "release_branch=$rbranch"
  echo "next_branch_version=$next_branch"
  echo "next_develop_version=$next_develop"
}

[ $# -ge 1 ] || die "usage: $0 <command> [args] (see the header of this script)"
CMD="$1"
shift
case "$CMD" in
  release-version | next-patch | next-minor) ;;
  *) cd_repo_root ;;
esac
case "$CMD:$#" in
  version:1) ext_py version "$1" ;;
  release-version:1) release_version "$1" ;;
  next-patch:1) next_patch "$1" ;;
  next-minor:1) next_minor "$1" ;;
  dev-version:2) dev_version "$1" "$2" ;;
  tag:2) tag_name "$1" "$2" ;;
  release-branch:2) release_branch "$1" "$2" ;;
  last-tag:1 | last-tag:2) last_tag "$@" ;;
  needs-release:1 | needs-release:2 | needs-release:3) needs_release "$@" ;;
  extensions-to-release:0 | extensions-to-release:1) extensions_to_release "$@" ;;
  patch-candidates:0 | patch-candidates:1) patch_candidates "$@" ;;
  plan:2) plan "$1" "$2" ;;
  *) die "unknown command or wrong number of arguments: $CMD $*" ;;
esac

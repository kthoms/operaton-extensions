#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: check-structure.sh <extension-path>      e.g. check-structure.sh connectors/kafka
#
# Verifies that an extension follows the homogeneous layout of
# docs/conventions/repository-design.md and exits non-zero, listing every deviation, if not.
#
# Every extension:
#   - extension.yml: valid manifest (kind matches the folder, name equals the folder, build,
#     compatibility of well-formed minors each with a compatibility/operaton-<minor>/ folder, ...)
#   - README.md with the headings "## When to use", "## Installation", "## Usage",
#     "## Configuration", "## Compatibility", "## Example", "## Development" in that order
#   - example/README.md
#   - NOTICE when a source file says "Ported from" or "Derived from"
# Java extensions (plugins, connectors; build: maven):
#   - pom.xml (artifactId operaton-<kind>-<name>-parent), example/pom.xml
#   - operaton-<kind>-<name>/pom.xml with src/test/java and the package folder
#     src/main/java/org/operaton/extensions/<kind>/<name-without-dashes>/
# Clients (clients/<language>; build: npm | python | go | cargo):
#   - the native project file (package.json | pyproject.toml | go.mod | Cargo.toml)
#   - tests (the language's usual test location)
set -uo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || die "usage: $0 <extension-path>"
EXT="${1%/}"
cd_repo_root

ERRORS=0
err() {
  ERRORS=$((ERRORS + 1))
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::$EXT: $*"; else echo "  ✗ $*"; fi
}
ok() { [ -n "${GITHUB_ACTIONS:-}" ] || echo "  ✓ $*"; }

echo "Checking the structure of $EXT"
[ -d "$EXT" ] || die "$EXT is not a directory"
case "$EXT" in
  plugins/* | connectors/* | clients/*) ;;
  *) die "$EXT is not below plugins/, connectors/ or clients/" ;;
esac
KINDS="${EXT%%/*}"
NAME="${EXT#*/}"
KIND="$(kind_of_path "$EXT")"

# ---------------------------------------------------------------- manifest
MANIFEST_ERRORS="$(ext_py manifest-check "$EXT")"
if [ -n "$MANIFEST_ERRORS" ]; then
  while IFS= read -r line; do err "$line"; done <<EOF
$MANIFEST_ERRORS
EOF
else
  ok "extension.yml"
fi
BUILD="$(manifest_get "$EXT" build 2>/dev/null)" || BUILD=""
MINORS="$(manifest_get "$EXT" compatibility 2>/dev/null)" || MINORS=""
IFS=',' read -r -a MINOR_LIST <<<"$MINORS"
for minor in ${MINOR_LIST[@]+"${MINOR_LIST[@]}"}; do
  minor="$(echo "$minor" | tr -d '[:space:]')"
  [ -n "$minor" ] || continue
  if [ -f "compatibility/operaton-$minor/pom.xml" ]; then
    ok "compatibility $minor -> compatibility/operaton-$minor"
  else
    err "extension.yml declares Operaton $minor, but compatibility/operaton-$minor/pom.xml does not exist"
  fi
done

# ---------------------------------------------------------------- README
REQUIRED_HEADINGS="When to use|Installation|Usage|Configuration|Compatibility|Example|Development"
if [ ! -f "$EXT/README.md" ]; then
  err "README.md is missing"
else
  # level-2 headings outside fenced code blocks, in document order
  HEADINGS="$(awk '/^(```|~~~)/ { fence = !fence; next } !fence && /^## / { sub(/^## +/, ""); sub(/[ \t]+$/, ""); print }' "$EXT/README.md")"
  last=0
  readme_ok=1
  IFS='|' read -r -a REQ <<<"$REQUIRED_HEADINGS"
  for h in "${REQ[@]}"; do
    pos="$(printf '%s\n' "$HEADINGS" | { grep -nxF "$h" || true; } | head -1 | cut -d: -f1)"
    if [ -z "$pos" ]; then
      err "README.md: heading '## $h' is missing"
      readme_ok=0
    elif [ "$pos" -lt "$last" ]; then
      err "README.md: heading '## $h' is out of order (required order: ${REQUIRED_HEADINGS//|/, })"
      readme_ok=0
    else
      last="$pos"
    fi
  done
  [ "$readme_ok" -eq 0 ] || ok "README.md headings"
fi

# ---------------------------------------------------------------- example
if [ -d "$EXT/example" ]; then
  if [ -f "$EXT/example/README.md" ]; then ok "example/README.md"; else err "example/README.md is missing"; fi
else
  err "example/ is missing"
fi

# ---------------------------------------------------------------- NOTICE for ports
PORTED="$(grep -rlE 'Ported from|Derived from' "$EXT" \
  --exclude=NOTICE --exclude='*.md' \
  --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=build \
  --exclude-dir=.venv --exclude-dir=venv --exclude-dir=__pycache__ --exclude-dir=.git 2>/dev/null || true)"
if [ -n "$PORTED" ]; then
  if [ -f "$EXT/NOTICE" ]; then
    ok "NOTICE present for ported code"
  else
    err "NOTICE is missing, but these files contain 'Ported from'/'Derived from': $(echo "$PORTED" | tr '\n' ' ')"
  fi
fi

# ---------------------------------------------------------------- Java extension
check_java() {
  local artifact="operaton-$KIND-$NAME" pkg
  if [ ! -f "$EXT/pom.xml" ]; then
    err "pom.xml (the extension aggregator) is missing"
  else
    local aid
    aid="$(python3 - "$EXT/pom.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for c in root:
    if c.tag.rsplit('}', 1)[-1] == 'artifactId':
        print((c.text or '').strip())
PY
)"
    if [ "$aid" = "$artifact-parent" ]; then ok "pom.xml artifactId $aid"; else err "pom.xml: artifactId is '$aid', expected '$artifact-parent'"; fi
  fi
  [ ! -d "$EXT/example" ] || [ -f "$EXT/example/pom.xml" ] || err "example/pom.xml is missing"
  if [ ! -f "$EXT/$artifact/pom.xml" ]; then
    err "the artifact module $artifact/ (with pom.xml) is missing"
    return
  fi
  ok "artifact module $artifact"
  if [ -d "$EXT/$artifact/src/test/java" ]; then ok "$artifact/src/test/java"; else err "$artifact/src/test/java is missing (unit and engine tests)"; fi
  pkg="org/operaton/extensions/$KIND/$(echo "$NAME" | tr -d -)"
  if [ -d "$EXT/$artifact/src/main/java/$pkg" ]; then
    ok "package $pkg"
  else
    err "$artifact/src/main/java/$pkg/ is missing (package org.operaton.extensions.$KIND.$(echo "$NAME" | tr -d -))"
  fi
}

# ---------------------------------------------------------------- client
check_client() {
  local expected_build="" native="" tests=0
  case "$NAME" in
    js) expected_build=npm ;;
    python) expected_build=python ;;
    go) expected_build=go ;;
    rust) expected_build=cargo ;;
    *) err "clients/$NAME: the folder must be named after the language (js, python, go, rust)" ;;
  esac
  [ -z "$expected_build" ] || [ "$BUILD" = "$expected_build" ] || err "extension.yml: build '$BUILD', expected '$expected_build' for clients/$NAME"
  case "$BUILD" in
    npm) native=package.json ;;
    python) native=pyproject.toml ;;
    go) native=go.mod ;;
    cargo) native=Cargo.toml ;;
    *) return ;;
  esac
  if [ -f "$EXT/$native" ]; then ok "$native"; else err "$native is missing (build: $BUILD)"; fi
  case "$BUILD" in
    npm)
      { [ -d "$EXT/test" ] || [ -d "$EXT/tests" ] || [ -d "$EXT/src/__tests__" ] ||
        [ -n "$(find "$EXT" -path "$EXT/node_modules" -prune -o -path "$EXT/example" -prune -o -type f \( -name '*.test.*' -o -name '*.spec.*' \) -print | head -1)" ]; } && tests=1 ;;
    python)
      { [ -d "$EXT/tests" ] || [ -n "$(find "$EXT" -path "$EXT/example" -prune -o -type f -name 'test_*.py' -print | head -1)" ]; } && tests=1 ;;
    go)
      [ -n "$(find "$EXT" -path "$EXT/example" -prune -o -type f -name '*_test.go' -print | head -1)" ] && tests=1
      if [ -f "$EXT/go.mod" ]; then
        module="$(awk '$1 == "module" { print $2; exit }' "$EXT/go.mod")"
        case "$module" in
          "github.com/kthoms/operaton-extensions/$EXT" | "github.com/kthoms/operaton-extensions/$EXT/v"[0-9]*) ok "go.mod module $module" ;;
          *) err "go.mod: module is '$module', expected 'github.com/kthoms/operaton-extensions/$EXT' (the tag $EXT/v<version> only works for that path)" ;;
        esac
      fi ;;
    cargo)
      { [ -d "$EXT/tests" ] || grep -rqs '#\[cfg(test)\]' "$EXT/src"; } && tests=1 ;;
  esac
  if [ "$tests" -eq 1 ]; then ok "tests"; else err "no tests found (the language's usual test location)"; fi
}

case "$KINDS" in
  plugins | connectors) check_java ;;
  clients) check_client ;;
esac

if [ "$ERRORS" -gt 0 ]; then
  echo "$EXT: $ERRORS structure error(s). See docs/conventions/repository-design.md" >&2
  exit 1
fi
echo "$EXT: structure OK"

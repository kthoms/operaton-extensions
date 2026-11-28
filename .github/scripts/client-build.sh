#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: client-build.sh <ext-path> <install|lint|unit|integration|package>
#
# Runs one build stage of a client (clients/<language>) with its language's own tooling, so CI
# and release workflows build clients the same way:
#
#   build   install                lint                                unit                         integration                    package
#   npm     npm ci                 npm run lint                        npm test                     npm run test:integration       npm run build --if-present
#   python  venv + pip -e .[dev]   ruff check .                        pytest -m "not integration"  pytest -m integration          python -m build
#   go      go mod download        go vet ./... + gofmt -l             go test ./...                go test -tags=integration ./...  (nothing)
#   cargo   cargo fetch            cargo fmt --check + clippy -D warn  cargo test                   cargo test --features integration  cargo package
#
# integration expects a running engine: OPERATON_URL (default http://localhost:8080/engine-rest)
# and OPERATON_VERSION (see operaton-engine.sh).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 2 ] || die "usage: $0 <ext-path> <install|lint|unit|integration|package>"
EXT="${1%/}"
STAGE="$2"
cd_repo_root
BUILD="$(manifest_get "$EXT" build)" || die "$EXT: extension.yml has no 'build'"
export OPERATON_URL="${OPERATON_URL:-http://localhost:8080/engine-rest}"
cd "$EXT"

run() { echo "+ $*" >&2; "$@"; }

case "$BUILD:$STAGE" in
  npm:install) run npm ci ;;
  npm:lint) run npm run lint ;;
  npm:unit) run npm test ;;
  npm:integration) run npm run test:integration ;;
  npm:package) run npm run build --if-present ;;

  python:install)
    run python3 -m venv .venv
    run .venv/bin/python -m pip install --upgrade pip
    run .venv/bin/python -m pip install -e '.[dev]' ;;
  python:lint) run .venv/bin/python -m ruff check . ;;
  python:unit) run .venv/bin/python -m pytest -m "not integration" ;;
  python:integration) run .venv/bin/python -m pytest -m integration ;;
  python:package)
    run .venv/bin/python -m pip install build
    run .venv/bin/python -m build ;;

  go:install) run go mod download ;;
  go:lint)
    run go vet ./...
    unformatted="$(gofmt -l .)"
    [ -z "$unformatted" ] || die "gofmt: not formatted: $unformatted" ;;
  go:unit) run go test ./... ;;
  go:integration) run go test -tags=integration ./... ;;
  go:package) echo "go: nothing to package, the tag is the release" >&2 ;;

  cargo:install) run cargo fetch ;;
  cargo:lint)
    run cargo fmt --all --check
    run cargo clippy --all-targets -- -D warnings ;;
  cargo:unit) run cargo test ;;
  cargo:integration) run cargo test --features integration ;;
  cargo:package) run cargo package ;;

  *) die "$EXT: no stage '$STAGE' for build '$BUILD'" ;;
esac

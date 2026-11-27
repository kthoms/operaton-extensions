# shellcheck shell=bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Shared helpers, sourced by the scripts in .github/scripts. Not executable on its own.
# Written for bash 3.2+ so the scripts run unchanged on macOS and on the Ubuntu runners.

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Folders that hold extensions: <kinds>/<name>/
EXTENSION_KINDS="plugins connectors clients"

# Prints an error (as a GitHub annotation when running in Actions) and exits.
die() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::$*" >&2; else echo "ERROR: $*" >&2; fi
  exit 1
}

warn() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::$*" >&2; else echo "WARNING: $*" >&2; fi
}

ext_py() { python3 "$SCRIPTS_DIR/ext.py" "$@"; }

# cd to the repository root (all paths in the scripts are relative to it).
cd_repo_root() {
  local root
  root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git repository"
  cd "$root" || die "cannot cd to $root"
}

# Lists every extension path (<kinds>/<name>) that exists at <ref> (default: the working tree).
# An extension is any folder directly below plugins/, connectors/ or clients/; check-structure.sh
# rejects one without a manifest, so a forgotten extension.yml cannot hide an extension from CI.
list_extensions() {
  local ref="${1:-}" kinds_re
  kinds_re="$(echo "$EXTENSION_KINDS" | tr ' ' '|')"
  if [ -n "$ref" ]; then
    git ls-tree -r --name-only "$ref" -- $EXTENSION_KINDS 2>/dev/null
  else
    git ls-files --cached --others --exclude-standard -- $EXTENSION_KINDS 2>/dev/null
  fi | { grep -E "^($kinds_re)/[^/]+/" || true; } | cut -d/ -f1-2 | sort -u
}

# Reads a manifest key; lists come back comma-joined. Empty output and status 3 when missing.
manifest_get() { ext_py manifest "$1" "$2"; }

# "plugins" -> "plugin", "connectors" -> "connector", "clients" -> "client"
kind_of_path() { echo "${1%%/*}" | sed 's/s$//'; }

# Prints a JSON array of the arguments.
json_array() { if [ $# -eq 0 ]; then echo '[]'; else jq -cn '$ARGS.positional' --args "$@"; fi; }

# The Maven artifact modules of a Java extension, as a comma-separated -pl list:
# sub-folders named operaton-<kind>-<name>* that contain a pom.xml (never example/).
artifact_modules() {
  local ext="$1" kind name d out=""
  kind="$(kind_of_path "$ext")"
  name="${ext#*/}"
  for d in "$ext"/operaton-"$kind"-"$name" "$ext"/operaton-"$kind"-"$name"-*; do
    [ -f "$d/pom.xml" ] || continue
    out="${out:+$out,}$d"
  done
  echo "$out"
}

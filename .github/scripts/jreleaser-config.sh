#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: jreleaser-config.sh <template> <ext-path> <out-file>
#
# Renders a JReleaser configuration template from .github/jreleaser/ for one extension.
# JReleaser resolves name templates ({{...}}, e.g. {{projectVersion}} or -P properties) only in
# some fields; project.name and project.description are not among them, so they are filled in here
# from the manifest:
#   __PROJECT_NAME__              operaton-<kind>-<name> (e.g. operaton-connector-kafka, operaton-client-js)
#   __PROJECT_DESCRIPTION__       extension.yml description
#   __PROJECT_LONG_DESCRIPTION__  extension.yml title
#   __EXTENSION_PATH__            <kinds>/<name> (inserted as is, for use inside quoted URLs)
# The other values are written as JSON strings, which are valid YAML double-quoted scalars.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 3 ] || die "usage: $0 <template> <ext-path> <out-file>"
TEMPLATE="$1"
EXT="${2%/}"
OUT="$3"
cd_repo_root

TITLE="$(manifest_get "$EXT" title)" || die "$EXT: extension.yml has no title"
DESCRIPTION="$(manifest_get "$EXT" description)" || die "$EXT: extension.yml has no description"
echo "$EXT" | grep -Eq '^(plugins|connectors|clients)/[a-z0-9]+(-[a-z0-9]+)*$' || die "'$EXT' is not an extension path"
NAME="operaton-$(kind_of_path "$EXT")-${EXT#*/}"

mkdir -p "$(dirname "$OUT")"
NAME="$NAME" TITLE="$TITLE" DESCRIPTION="$DESCRIPTION" EXT="$EXT" python3 - "$TEMPLATE" "$OUT" <<'PY'
import json, os, sys
text = open(sys.argv[1], encoding="utf-8").read()
for placeholder, key in (("__PROJECT_NAME__", "NAME"), ("__PROJECT_DESCRIPTION__", "DESCRIPTION"),
                         ("__PROJECT_LONG_DESCRIPTION__", "TITLE")):
    text = text.replace(placeholder, json.dumps(os.environ[key], ensure_ascii=False))
# the path is validated (<kinds>/<name>, lower-case kebab-case) and used inside quoted URLs
text = text.replace("__EXTENSION_PATH__", os.environ["EXT"])
open(sys.argv[2], "w", encoding="utf-8").write(text)
PY
echo "rendered $TEMPLATE for $EXT into $OUT" >&2

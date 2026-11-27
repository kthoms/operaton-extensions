#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: compat-new-minor.sh <maven-metadata.xml> [--create]
#
# Reads the maven-metadata.xml of org.operaton.bpm:operaton-bom (Maven Central) and prints a JSON
# array of the Operaton minors newer than the newest compatibility/operaton-<minor>/ folder, each
# with its latest final patch: [{"minor":"2.3","version":"2.3.0"}]. Pre-releases (-M1, -RC1, ...)
# are ignored. Older minors without a folder are not proposed (they were dropped on purpose).
#
# With --create, adds compatibility/operaton-<minor>/pom.xml for each, copied from the newest
# existing folder with the minor and the pinned version replaced.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || { [ $# -eq 2 ] && [ "$2" = --create ]; } || die "usage: $0 <maven-metadata.xml> [--create]"
METADATA="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
CREATE="${2:-}"
cd_repo_root

[ -f "$METADATA" ] || die "$METADATA not found"
NEWEST_EXISTING="$(ls -d compatibility/operaton-*/ 2>/dev/null | sed -E 's|compatibility/operaton-([0-9]+\.[0-9]+)/|\1|' |
  grep -E '^[0-9]+\.[0-9]+$' | sort -t. -k1,1n -k2,2n | tail -1 || true)"
[ -n "$NEWEST_EXISTING" ] || die "no compatibility/operaton-<minor>/ folder found"

RESULT="$(python3 - "$METADATA" "$NEWEST_EXISTING" <<'PY'
import json, re, sys
import xml.etree.ElementTree as ET
metadata, newest = sys.argv[1], tuple(int(x) for x in sys.argv[2].split("."))
latest = {}
for v in ET.parse(metadata).getroot().iter("version"):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", (v.text or "").strip())
    if not m:
        continue
    minor, patch = (int(m.group(1)), int(m.group(2))), int(m.group(3))
    if minor > newest and patch >= latest.get(minor, -1):
        latest[minor] = patch
print(json.dumps([{"minor": f"{a}.{b}", "version": f"{a}.{b}.{p}"} for (a, b), p in sorted(latest.items())],
                 separators=(",", ":")))
PY
)"

if [ "$CREATE" = --create ]; then
  TEMPLATE="compatibility/operaton-$NEWEST_EXISTING/pom.xml"
  OLD_VERSION="$(ext_py compat-version "$TEMPLATE")"
  for row in $(echo "$RESULT" | jq -r '.[] | "\(.minor)=\(.version)"'); do
    minor="${row%%=*}"
    version="${row#*=}"
    mkdir -p "compatibility/operaton-$minor"
    OLD_MINOR="$NEWEST_EXISTING" NEW_MINOR="$minor" OLD_VERSION="$OLD_VERSION" NEW_VERSION="$version" python3 - "$TEMPLATE" "compatibility/operaton-$minor/pom.xml" <<'PY'
import os, sys
src, dst = sys.argv[1], sys.argv[2]
e = os.environ
text = open(src, encoding="utf-8").read()
text = text.replace(f"<operaton.version>{e['OLD_VERSION']}</operaton.version>", f"<operaton.version>{e['NEW_VERSION']}</operaton.version>")
text = text.replace(f"<version>{e['OLD_VERSION']}</version>", f"<version>{e['NEW_VERSION']}</version>")
text = text.replace(f"operaton-{e['OLD_MINOR']}", f"operaton-{e['NEW_MINOR']}")
text = text.replace(f"Operaton {e['OLD_MINOR']}", f"Operaton {e['NEW_MINOR']}")
text = text.replace(f"{e['OLD_MINOR']}.x", f"{e['NEW_MINOR']}.x")
open(dst, "w", encoding="utf-8").write(text)
PY
    [ "$(ext_py compat-version "compatibility/operaton-$minor/pom.xml")" = "$version" ] ||
      die "compatibility/operaton-$minor/pom.xml: could not set version $version"
    echo "created compatibility/operaton-$minor (Operaton $version)" >&2
  done
fi
echo "$RESULT"

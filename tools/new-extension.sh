#!/usr/bin/env bash
# Creates a new extension from tools/extension-template/ so every module starts with the same shape.
#
#   tools/new-extension.sh plugin <name> ["Title"]
#   tools/new-extension.sh connector <name> ["Title"]
#   tools/new-extension.sh client <js|python|go|rust> ["Title"]
#
# Java extensions (plugin, connector) get the full template and are registered in
# plugins/pom.xml or connectors/pom.xml. A client gets the manifest, README and example skeleton;
# its native project (package.json, pyproject.toml, go.mod, Cargo.toml) is written by hand,
# following docs/conventions/clients.md.
# See docs/conventions/contributing-an-extension.md.
set -euo pipefail

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[[ $# -ge 2 && $# -le 3 ]] || usage
KIND=$1
NAME=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEMPLATE="$ROOT/tools/extension-template"

case "$KIND" in
  plugin | connector) KINDS="${KIND}s" ;;
  client)
    KINDS=clients
    [[ "$NAME" =~ ^(js|python|go|rust)$ ]] || { echo "error: client language must be js, python, go or rust" >&2; exit 2; }
    ;;
  *) usage ;;
esac

if [[ ! "$NAME" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ ]]; then
  echo "error: name '$NAME' must be lower-case kebab-case (e.g. kafka, history-metrics)" >&2
  exit 2
fi

TARGET="$ROOT/$KINDS/$NAME"
if [[ -e "$TARGET" ]]; then
  echo "error: $KINDS/$NAME already exists" >&2
  exit 1
fi

# Title: given, or derived from the name ("history-metrics" -> "History Metrics <Kind>")
if [[ $# -eq 3 ]]; then
  TITLE=$3
else
  TITLE=$(echo "$NAME" | awk -F- '{for (i = 1; i <= NF; i++) $i = toupper(substr($i, 1, 1)) substr($i, 2)} 1' OFS=' ')
  KIND_TITLE=$(echo "$KIND" | awk '{print toupper(substr($0, 1, 1)) substr($0, 2)}')
  TITLE="$TITLE $KIND_TITLE"
fi
PKG=${NAME//-/}

substitute() { # file
  sed -i.bak \
    -e "s/__KINDS__/$KINDS/g" -e "s/__KIND__/$KIND/g" -e "s/__NAME__/$NAME/g" \
    -e "s/__PKG__/$PKG/g" -e "s/__TITLE__/$TITLE/g" "$1"
  rm -f "$1.bak"
}

if [[ "$KIND" == client ]]; then
  SRC="$TEMPLATE/client"
else
  SRC="$TEMPLATE/java"
fi

mkdir -p "$TARGET"
cp -R "$SRC/." "$TARGET/"

# Rename the placeholder paths, deepest first
find "$TARGET" -depth -name '*__*' | while read -r path; do
  dir=$(dirname "$path")
  base=$(basename "$path")
  base=${base//__KIND__/$KIND}
  base=${base//__NAME__/$NAME}
  base=${base//__PKG__/$PKG}
  mv "$path" "$dir/$base"
done
find "$TARGET" -type f | while read -r file; do substitute "$file"; done

if [[ "$KIND" == client ]]; then
  case "$NAME" in js) BUILD=npm ;; python) BUILD=python ;; go) BUILD=go ;; rust) BUILD=cargo ;; esac
  sed -i.bak "s/^build: .*/build: $BUILD/" "$TARGET/extension.yml" && rm -f "$TARGET/extension.yml.bak"
fi

if [[ "$KIND" != client ]]; then
  AGG="$ROOT/$KINDS/pom.xml"
  if ! grep -q "<module>$NAME</module>" "$AGG"; then
    # insert before the end marker, keeping the list sorted is left to the reviewer
    MODULE="$NAME" perl -0pi -e 's|(\n\s*<!-- extensions:end -->)|\n    <module>$ENV{MODULE}</module>$1|' "$AGG"
  fi
fi

echo "Created $KINDS/$NAME"
echo "Next:"
echo "  1. fill extension.yml (description, compatibility) and the TODOs in README.md and example/README.md"
[[ "$KIND" == client ]] && echo "  2. add the native project for '$NAME' (docs/conventions/clients.md)"
[[ "$KIND" != client ]] && echo "  2. implement under $KINDS/$NAME/operaton-$KIND-$NAME/, then: ./mvnw -B -pl $KINDS/$NAME -amd verify"
echo "  3. add the catalog row in docs/catalog.md"

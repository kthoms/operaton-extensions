#!/usr/bin/env bash
# Copyright the Operaton contributors.
# SPDX-License-Identifier: Apache-2.0
#
# Usage: operaton-engine.sh start <operaton-version> | stop
#
# Starts the Docker image operaton/operaton:<version> on port 8080 and waits until
# /engine-rest/engine answers (up to 5 minutes). Used for the integration tests of the clients.
# In GitHub Actions it also exports OPERATON_VERSION and OPERATON_URL to later steps.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

CONTAINER=operaton-engine
case "${1:-}:$#" in
  start:2)
    VERSION="$2"
    echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "'$VERSION' is not an Operaton version"
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$CONTAINER" -p 8080:8080 "operaton/operaton:$VERSION" >/dev/null
    URL="http://localhost:8080/engine-rest"
    for _ in $(seq 1 60); do
      if curl -fsS "$URL/engine" >/dev/null 2>&1; then
        echo "Operaton $VERSION is up at $URL" >&2
        if [ -n "${GITHUB_ENV:-}" ]; then
          { echo "OPERATON_VERSION=$VERSION"; echo "OPERATON_URL=$URL"; } >>"$GITHUB_ENV"
        fi
        exit 0
      fi
      sleep 5
    done
    docker logs "$CONTAINER" >&2 || true
    die "Operaton $VERSION did not start within 5 minutes" ;;
  stop:1) docker rm -f "$CONTAINER" >/dev/null 2>&1 || true ;;
  *) die "usage: $0 start <operaton-version> | stop" ;;
esac

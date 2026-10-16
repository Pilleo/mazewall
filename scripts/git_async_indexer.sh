#!/usr/bin/env bash
# Refresh optional code intelligence after incoming commits or branch switches.
# Safely no-ops whenever optional tools or dependencies are not available.

set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if [ -x "$ROOT_DIR/scripts/adk-codanna-reindex" ]; then
    "$ROOT_DIR/scripts/adk-codanna-reindex" || true
fi

exit 0

#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ ! -x "$root/build/mw-hotspot-demo" ]]; then
    printf '%s\n' 'Build the experiment first: make build' >&2
    exit 2
fi
exec "$root/build/mw-hotspot-demo" --preflight "$@"

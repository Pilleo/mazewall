#!/usr/bin/env bash
# Tier E WP-03 Gate G0a runner — same backend policy as run_collector.sh.
# Acceptance: phase 1 events carry ctx=42, phase 2 ctx=7, post-marker(0) silent.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DURATION=8
[[ "${1:-}" == "--duration" && -n "${2:-}" ]] && { DURATION="$2"; shift 2; }
[[ $# -eq 0 ]] || { echo "usage: $0 [--duration N]" >&2; exit 2; }

# shellcheck source=_backend.sh
source "$ROOT/scripts/_backend.sh"
_tier_e_detect_backend
_ensure_runner_image

echo "[tier-e] backend: ${BACKEND[*]}"
exec "${BACKEND[@]}" run \
    --rm \
    --privileged \
    --userns=host \
    --pid=host \
    --network=host \
    -e DURATION="$DURATION" \
    -v "$ROOT":/work \
    -w /work \
    "$IMAGE" \
    bash /work/scripts/_container_inner_wp03.sh

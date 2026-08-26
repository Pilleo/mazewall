#!/usr/bin/env bash
# Tier E WP-03 Gate G1 runner — same backend policy as run_wp03.sh.
# Usage: scripts/run_g1.sh [iterations]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
    -e ITERS="${1:-10000000}" \
    -v "$ROOT":/work \
    -w /work \
    "$IMAGE" \
    bash /work/scripts/_container_inner_g1.sh

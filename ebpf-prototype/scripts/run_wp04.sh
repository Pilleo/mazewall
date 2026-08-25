#!/usr/bin/env bash
# Tier E WP-04 runner — shared backend policy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/_backend.sh"
_tier_e_detect_backend
_ensure_runner_image
echo "[tier-e] backend: ${BACKEND[*]}"
exec "${BACKEND[@]}" run --rm --privileged --userns=host --pid=host --network=host \
    -v "$ROOT":/work -w /work "$IMAGE" bash /work/scripts/_container_inner_wp04.sh

#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${1:-"${ROOT}/build/tier-e-split-negative-matrix"}
export MAZEWALL_DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
export DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
if [[ "${TIER_E_SPLIT_REUSE_BUILD:-0}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
exec bash "${ROOT}/scripts/tier_e_tools" negative-matrix "${ROOT}/config/testing/tier-e/smoke.json" "${ROOT}" "${OUTPUT}"

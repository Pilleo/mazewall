#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${1:-"${ROOT}/build/tier-e-hybrid-live-comparison"}
FORKS=${TIER_E_HYBRID_LIVE_FORKS:-5}
ITERATIONS=${TIER_E_HYBRID_LIVE_ITERATIONS:-200}
PROFILES=${TIER_E_HYBRID_LIVE_PROFILES:-syscall_dense,stack_diverse,class_churn}
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
export MAZEWALL_DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
export DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
if [[ "${TIER_E_SPLIT_REUSE_BUILD:-0}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
exec bash "${ROOT}/scripts/tier_e_tools" live-matrix "${ROOT}/config/testing/tier-e/smoke.json" "${ROOT}" "${OUTPUT}" "${FORKS}" "${ITERATIONS}" "${PROFILES}"

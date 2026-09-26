#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${1:-"${ROOT}/build/tier-e-hybrid-fault-matrix"}
FORKS=${TIER_E_HYBRID_FAULT_FORKS:-15}
OPERATIONS=${TIER_E_HYBRID_FAULT_OPERATIONS:-72}
BACKEND=${TIER_E_HYBRID_FAULT_BACKEND:-uprobe}
if [[ "${TIER_E_HYBRID_FAULT_LIVE:-0}" != 0 || "${TIER_E_HYBRID_FAULT_PHASES:-0}" != 0 ]]; then
    echo 'live snapshots and phase instrumentation are not owned by the Kotlin fault matrix' >&2
    exit 2
fi
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${OPERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
[[ "${BACKEND}" == uprobe || "${BACKEND}" == usdt ]] || exit 2
export MAZEWALL_DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
export DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
if [[ "${TIER_E_SPLIT_REUSE_BUILD:-0}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
exec bash "${ROOT}/scripts/tier_e_tools" fault-matrix "${ROOT}/config/testing/tier-e/fault-recovery.json" "${ROOT}" "${OUTPUT}" "${FORKS}" "${OPERATIONS}" "${BACKEND}"

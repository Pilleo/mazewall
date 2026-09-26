#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${1:-"${ROOT}/build/tier-e-split-coverage-matrix"}
VARIANTS=${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}
LONG=${TIER_E_SPLIT_COVERAGE_LONG_MATCHED:-0}
FORKS=${TIER_E_SPLIT_COVERAGE_FORKS:-1}
HYBRID_ONLY=${TIER_E_SPLIT_COVERAGE_HYBRID_ONLY:-0}
if [[ "${TIER_E_SPLIT_SKIP_EXISTING:-0}" == 1 || "${TIER_E_SPLIT_ALLOW_INCOMPLETE:-0}" == 1 ]]; then
    echo 'skip-existing and allowed-incomplete coverage are not owned by the Kotlin matrix launcher' >&2
    exit 2
fi
if [[ "${TIER_E_SPLIT_LONG_ARM_TIMEOUT:-600}" != 600 ]]; then
    echo 'arm timeout override is not owned by the Kotlin matrix launcher' >&2
    exit 2
fi
[[ "${VARIANTS}" == legacy || "${VARIANTS}" == hybrid ]] || { echo 'unknown coverage matrix variant set' >&2; exit 2; }
[[ "${LONG}" == 0 || "${LONG}" == 1 ]] || { echo 'long matched flag must be 0 or 1' >&2; exit 2; }
[[ "${HYBRID_ONLY}" == 0 || "${HYBRID_ONLY}" == 1 ]] || { echo 'hybrid-only flag must be 0 or 1' >&2; exit 2; }
if [[ "${LONG}" == 1 ]]; then
    OPERATIONS=${TIER_E_SPLIT_COVERAGE_OPERATIONS:-75000}
    PROFILE="${ROOT}/config/testing/tier-e/long-matched.json"
else
    OPERATIONS=${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}
    PROFILE="${ROOT}/config/testing/tier-e/coverage.json"
fi
export MAZEWALL_DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
export DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
if [[ "${TIER_E_SPLIT_REUSE_BUILD:-0}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
exec bash "${ROOT}/scripts/tier_e_tools" coverage-matrix "${PROFILE}" "${ROOT}" "${OUTPUT}" "${VARIANTS}" "${LONG}" "${FORKS}" "${OPERATIONS}" "${HYBRID_ONLY}"

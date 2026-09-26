#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LEVEL=${1:-focused}
if [[ "${LEVEL}" == --help || "${LEVEL}" == -h ]]; then
    echo 'Usage: scripts/run_tier_e_checks.sh <focused|native|kernel-smoke|delivery>'
    exit 0
fi
[[ $# -le 1 ]] || { echo 'expected one check level' >&2; exit 2; }
RUN_ID=${MAZEWALL_TIER_E_CHECK_RUN_ID:-"$(date -u +%Y%m%dT%H%M%SZ)-$$"}
OUTPUT=${MAZEWALL_TIER_E_CHECK_ARTIFACT_DIR:-"${ROOT}/build/tier-e-checks/${RUN_ID}"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" tier-e-checks "${LEVEL}" "${ROOT}" "${OUTPUT}"

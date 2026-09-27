#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == --help ]]; then
    echo 'Usage: scripts/run_tier_e_sustained_capacity.sh [artifact-directory] [profile.json]'
    echo 'A selected profile supplies the schedule; without one the historical environment adapter is used.'
    exit 0
fi
[[ $# -le 2 ]] || { echo 'expected artifact directory and optional profile' >&2; exit 2; }
OUTPUT=${1:-"${ROOT}/build/tier-e-sustained-capacity"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
if [[ $# == 2 ]]; then
    exec bash "${ROOT}/scripts/tier_e_tools" capacity-profile "$2" "${ROOT}" "${OUTPUT}"
fi
exec bash "${ROOT}/scripts/tier_e_tools" capacity-run "${ROOT}" "${OUTPUT}"

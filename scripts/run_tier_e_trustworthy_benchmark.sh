#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == --help ]]; then
    echo 'Usage: scripts/run_tier_e_trustworthy_benchmark.sh [artifact-directory] [profile.json]'
    echo 'Uses the checked-in profile by default; pass --legacy-env or set MAZEWALL_TIER_E_* for historical replay.'
    exit 0
fi
[[ $# -le 2 ]] || { echo 'expected artifact directory and optional profile' >&2; exit 2; }
OUTPUT=${1:-"${ROOT}/build/tier-e-trustworthy-benchmark"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
if [[ "${2:-}" == --legacy-env ]] || { [[ $# -le 1 ]] && compgen -e MAZEWALL_TIER_E_ >/dev/null; }; then
    exec bash "${ROOT}/scripts/tier_e_tools" trustworthy-run "${ROOT}" "${OUTPUT}"
fi
exec bash "${ROOT}/scripts/tier_e_tools" experiment-run "${2:-${ROOT}/config/testing/tier-e/trustworthy.json}" "${ROOT}" "${OUTPUT}"

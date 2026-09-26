#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == --help ]]; then
    echo 'Usage: scripts/run_tier_e_trustworthy_benchmark.sh [artifact-directory]'
    echo 'Runs a rootful Tier E benchmark from MAZEWALL_TIER_E_* settings.'
    exit 0
fi
[[ $# -le 1 ]] || { echo 'expected at most one artifact directory' >&2; exit 2; }
OUTPUT=${1:-"${ROOT}/build/tier-e-trustworthy-benchmark"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" trustworthy-run "${ROOT}" "${OUTPUT}"

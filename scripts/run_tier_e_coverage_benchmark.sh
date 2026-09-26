#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LEVEL=${1:-smoke}
[[ "${LEVEL}" == smoke || "${LEVEL}" == full ]] || {
    echo 'usage: bash scripts/run_tier_e_coverage_benchmark.sh smoke|full [artifact-directory]' >&2
    exit 2
}
[[ $# -le 2 ]] || { echo 'expected at most two arguments' >&2; exit 2; }
OUTPUT=${2:-"${ROOT}/build/tier-e-coverage-${LEVEL}"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" coverage-run "${LEVEL}" "${ROOT}" "${OUTPUT}"

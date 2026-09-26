#!/usr/bin/env bash
set -euo pipefail

# Saved coverage experiment. Both runs use identical code, transport settings and corpus paths.
LEVEL=${1:-smoke}
case "${LEVEL}" in
    smoke) COVERAGE_BATCHES=24; COVERAGE_FORKS=1 ;;
    full) COVERAGE_BATCHES=750; COVERAGE_FORKS=5 ;;
    *) printf '%s\n' 'usage: bash scripts/run_tier_e_coverage_benchmark.sh smoke|full [artifact-directory]' >&2; exit 2 ;;
esac
REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARTIFACT_DIR=${2:-"${REPOSITORY_DIR}/build/tier-e-coverage-${LEVEL}"}
"${REPOSITORY_DIR}/gradlew" :profiler:installDist >/dev/null
export MAZEWALL_TIER_E_RUN_LEVEL=custom
export MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-coverage-discovery
export MAZEWALL_TIER_E_BENCHMARK_WORKLOAD=oracle
export MAZEWALL_TIER_E_CALIBRATION=false
export MAZEWALL_TIER_E_WARMUPS=3
export MAZEWALL_TIER_E_ITERATIONS=100
export MAZEWALL_TIER_E_BATCHES=${COVERAGE_BATCHES}
export MAZEWALL_TIER_E_FORKS=${COVERAGE_FORKS}
export MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND=1250
export MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK=monotonic
RUN_STATUS=0
bash "${REPOSITORY_DIR}/scripts/run_tier_e_trustworthy_benchmark.sh" "${ARTIFACT_DIR}" || RUN_STATUS=$?
if [[ -f "${ARTIFACT_DIR}/results.jsonl" ]]; then
    bash "${REPOSITORY_DIR}/scripts/tier_e_tools" coverage-summary "${ARTIFACT_DIR}"
fi
exit "${RUN_STATUS}"

#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARTIFACT_DIR=${1:-"${REPOSITORY_DIR}/build/tier-e-sustained-capacity"}
PILOT_RATES=${MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES:-"1000 2000 4000 8000 16000 32000 64000"}
RUN_LEVEL=${MAZEWALL_TIER_E_SUSTAINED_RUN_LEVEL:-smoke}
LOAD=${MAZEWALL_TIER_E_SUSTAINED_LOAD:-below_capacity}

if [[ "${1:-}" == "--help" ]]; then
    cat <<'EOF'
Usage: scripts/run_tier_e_sustained_capacity.sh [artifact-directory]

Runs ordered sustained Tier E pilot stages through the trusted rootful runner.
Each stage retains its own JSONL, manifest, logs, and terminal record. Configure
rates with MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES and the run level with
MAZEWALL_TIER_E_SUSTAINED_RUN_LEVEL.
EOF
    exit 0
fi

mkdir -p "${ARTIFACT_DIR}"
EVENTS="${ARTIFACT_DIR}/capacity-runner-events.jsonl"
terminal=false

event() {
    jq -cn \
        --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --arg stage "$1" \
        --arg state "$2" \
        --arg message "${3:-}" \
        '{kind:"sustained_capacity_stage", timestamp:$timestamp, stage:$stage, state:$state, message:$message}' >> "${EVENTS}"
}

finish() {
    local status=$?
    trap - EXIT
    if [[ "${terminal}" == false ]]; then
        event runner FAILED "exit=${status}"
    fi
    exit "${status}"
}
trap finish EXIT

event runner STARTED "run_level=${RUN_LEVEL} load=${LOAD} rates=${PILOT_RATES}"
for rate in ${PILOT_RATES}; do
    [[ "${rate}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained rate: ${rate}" >&2; exit 2; }
    stage="${ARTIFACT_DIR}/rate-${rate}"
    event "rate-${rate}" STARTED
    MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-sustained-collector \
        MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND="${rate}" \
        MAZEWALL_TIER_E_SUSTAINED_LOAD="${LOAD}" \
        MAZEWALL_TIER_E_RUN_LEVEL="${RUN_LEVEL}" \
        "${REPOSITORY_DIR}/scripts/run_tier_e_trustworthy_benchmark.sh" "${stage}"
    jq -e -s '
        ([.[] | select(.kind == "terminal" and .success == true)] | length == 1) and
        ([.[] | select(.kind == "fork-output" and .sustained_collector.evidence_integrity == "COMPLETE"] | length > 0)
    ' "${stage}/results.jsonl" > /dev/null
    event "rate-${rate}" COMPLETED
done
event runner PASSED "all pilot stages completed"
terminal=true

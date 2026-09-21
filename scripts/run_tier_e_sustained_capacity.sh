#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ARTIFACT_DIR=${1:-"${REPOSITORY_DIR}/build/tier-e-sustained-capacity"}
PILOT_RATES=${MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES:-"1000 2000 4000 8000 16000 32000 64000"}
RUN_LEVEL=${MAZEWALL_TIER_E_SUSTAINED_RUN_LEVEL:-smoke}
LOAD=${MAZEWALL_TIER_E_SUSTAINED_LOAD:-below_capacity}
RECEIPT_CLOCK=${MAZEWALL_TIER_E_SUSTAINED_RECEIPT_CLOCK:-monotonic}
STAGE_SECONDS=${MAZEWALL_TIER_E_SUSTAINED_STAGE_SECONDS:-10}
ITERATIONS=${MAZEWALL_TIER_E_SUSTAINED_ITERATIONS:-200}
MAX_RETAINED_RECORDS=${MAZEWALL_TIER_E_SUSTAINED_MAX_RETAINED_RECORDS:-100000}
RETAINED_BUDGET_BYTES=${MAZEWALL_TIER_E_SUSTAINED_RETAINED_BUDGET_BYTES:-67108864}
RUN_DECISION=${MAZEWALL_TIER_E_SUSTAINED_RUN_DECISION:-false}
DECISION_FORKS=${MAZEWALL_TIER_E_SUSTAINED_DECISION_FORKS:-5}
DECISION_STAGE_SECONDS=${MAZEWALL_TIER_E_SUSTAINED_DECISION_STAGE_SECONDS:-60}
SHADOW_ON_FAILURE=${MAZEWALL_TIER_E_SUSTAINED_SHADOW_ON_FAILURE:-true}

if [[ "${1:-}" == "--help" ]]; then
    cat <<'EOF'
Usage: scripts/run_tier_e_sustained_capacity.sh [artifact-directory]

Runs ordered sustained Tier E pilot stages through the trusted rootful runner.
Each stage retains its own JSONL, manifest, logs, and terminal record. Configure
rates with MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES and the run level with
MAZEWALL_TIER_E_SUSTAINED_RUN_LEVEL. The first non-eligible pilot ends the
search and freezes the prior eligible rate in capacity-manifest.json. Set
MAZEWALL_TIER_E_SUSTAINED_RUN_DECISION=true to run five-fork decision stages
at 50%, 75%, and 90% of that frozen ceiling. Decision runs require a clean
Git checkout; smoke and diagnostic runs may retain source-dirty provenance.
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

[[ "${STAGE_SECONDS}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained stage seconds: ${STAGE_SECONDS}" >&2; exit 2; }
[[ "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained iterations: ${ITERATIONS}" >&2; exit 2; }
[[ "${MAX_RETAINED_RECORDS}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained max retained records: ${MAX_RETAINED_RECORDS}" >&2; exit 2; }
[[ "${RETAINED_BUDGET_BYTES}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained retained budget: ${RETAINED_BUDGET_BYTES}" >&2; exit 2; }
[[ "${DECISION_FORKS}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained decision forks: ${DECISION_FORKS}" >&2; exit 2; }
[[ "${DECISION_STAGE_SECONDS}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained decision stage seconds: ${DECISION_STAGE_SECONDS}" >&2; exit 2; }
[[ "${RUN_DECISION}" == true || "${RUN_DECISION}" == false ]] || { echo "MAZEWALL_TIER_E_SUSTAINED_RUN_DECISION must be true or false" >&2; exit 2; }
[[ "${SHADOW_ON_FAILURE}" == true || "${SHADOW_ON_FAILURE}" == false ]] || { echo "MAZEWALL_TIER_E_SUSTAINED_SHADOW_ON_FAILURE must be true or false" >&2; exit 2; }

eligible_stage() {
    local results=$1
    jq -e -s --argjson retained_limit "$((RETAINED_BUDGET_BYTES * 3 / 4))" '
        def sustained_fork:
            select(.kind == "fork-output" and (.sustained_collector != null));
        (([.[] | select(.kind == "terminal" and .success == true)] | length) == 1) and
        (([.[] | sustained_fork] | length) > 0) and
        (([.[] | sustained_fork |
            ((.sustained_collector.evidence_integrity == "COMPLETE") and
             (.sustained_collector.performance_eligible == true) and
             (.sustained_collector.retained_state_high_water_bytes <= $retained_limit))
        ] | length) == ([.[] | sustained_fork] | length))
    ' "${results}" > /dev/null
}

run_stage() {
    local rate=$1
    local seconds=$2
    local forks=$3
    local label=$4
    local total_operations=$((rate * seconds))
    (( total_operations % ITERATIONS == 0 )) || {
        echo "sustained rate ${rate} for ${seconds}s is not divisible by ${ITERATIONS} operations per batch" >&2
        return 2
    }
    local batches=$((total_operations / ITERATIONS))
    STAGE_PATH="${ARTIFACT_DIR}/${label}-rate-${rate}"
    event "${label}-rate-${rate}" STARTED "forks=${forks} batches=${batches} expected_operations=${total_operations}"
    MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-sustained-collector \
        MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND="${rate}" \
        MAZEWALL_TIER_E_SUSTAINED_LOAD="${LOAD}" \
        MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK="${RECEIPT_CLOCK}" \
        MAZEWALL_TIER_E_CALIBRATION=false \
        MAZEWALL_TIER_E_ITERATIONS="${ITERATIONS}" \
        MAZEWALL_TIER_E_BATCHES="${batches}" \
        MAZEWALL_TIER_E_FORKS="${forks}" \
        MAZEWALL_TIER_E_RUN_LEVEL=custom \
        MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS="${MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS:-} -Dmazewall.tier-e.benchmark-max-retained-records=${MAX_RETAINED_RECORDS}" \
        bash "${REPOSITORY_DIR}/scripts/run_tier_e_trustworthy_benchmark.sh" "${STAGE_PATH}"
}

run_shadow_stage() {
    local rate=$1
    local seconds=$2
    local label=$3
    local total_operations=$((rate * seconds))
    (( total_operations % ITERATIONS == 0 )) || return 2
    local batches=$((total_operations / ITERATIONS))
    local shadow_path="${ARTIFACT_DIR}/${label}-rate-${rate}-shadow"
    event "${label}-rate-${rate}-shadow" STARTED "diagnostic_replay=true forks=1 batches=${batches} expected_operations=${total_operations}"
    if MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-sustained-collector \
        MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND="${rate}" \
        MAZEWALL_TIER_E_SUSTAINED_LOAD="${LOAD}" \
        MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK="${RECEIPT_CLOCK}" \
        MAZEWALL_TIER_E_CALIBRATION=false \
        MAZEWALL_TIER_E_ITERATIONS="${ITERATIONS}" \
        MAZEWALL_TIER_E_BATCHES="${batches}" \
        MAZEWALL_TIER_E_FORKS=1 \
        MAZEWALL_TIER_E_RUN_LEVEL=custom \
        MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS="${MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS:-} -Dmazewall.tier-e.benchmark-max-retained-records=${MAX_RETAINED_RECORDS} -Dmazewall.tier-e.benchmark-strace-output=/artifacts/strace-shadow.log" \
        bash "${REPOSITORY_DIR}/scripts/run_tier_e_trustworthy_benchmark.sh" "${shadow_path}"; then
        event "${label}-rate-${rate}-shadow" COMPLETED "diagnostic_replay=true artifact=${shadow_path}"
    else
        event "${label}-rate-${rate}-shadow" INCOMPLETE "diagnostic_replay=true artifact=${shadow_path}"
    fi
}

event runner STARTED "run_level=${RUN_LEVEL} load=${LOAD} rates=${PILOT_RATES} stage_seconds=${STAGE_SECONDS} iterations=${ITERATIONS} max_retained_records=${MAX_RETAINED_RECORDS} retained_budget_bytes=${RETAINED_BUDGET_BYTES} run_decision=${RUN_DECISION} shadow_on_failure=${SHADOW_ON_FAILURE}"
if [[ "${RUN_DECISION}" == true ]]; then
    source_state=$(bash "${REPOSITORY_DIR}/scripts/tier_e_benchmark_source_state.sh" "${REPOSITORY_DIR}")
    benchmark_input_dirty_count=$(jq -r '.benchmark_input_dirty_count' <<< "${source_state}")
    all_dirty_count=$(jq -r '.all_dirty_count' <<< "${source_state}")
    if (( benchmark_input_dirty_count > 0 )); then
        event runner REJECTED "benchmark_input_dirty_decision_rejected; benchmark_input_dirty_entries=${benchmark_input_dirty_count}; all_dirty_entries=${all_dirty_count}"
        terminal=true
        exit 2
    fi
    event runner PROVENANCE "benchmark_input_dirty_entries=0; unrelated_dirty_entries=$((all_dirty_count - benchmark_input_dirty_count))"
fi
ceiling_rate=0
for rate in ${PILOT_RATES}; do
    [[ "${rate}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid sustained rate: ${rate}" >&2; exit 2; }
    if ! run_stage "${rate}" "${STAGE_SECONDS}" 1 pilot; then
        [[ "${SHADOW_ON_FAILURE}" == true ]] && run_shadow_stage "${rate}" "${STAGE_SECONDS}" pilot
        event "pilot-rate-${rate}" INCOMPLETE "runner_failure; ceiling_rate=${ceiling_rate}"
        break
    fi
    if eligible_stage "${STAGE_PATH}/results.jsonl"; then
        ceiling_rate=${rate}
        event "pilot-rate-${rate}" COMPLETED "eligible=true"
    else
        [[ "${SHADOW_ON_FAILURE}" == true ]] && run_shadow_stage "${rate}" "${STAGE_SECONDS}" pilot
        event "pilot-rate-${rate}" INCOMPLETE "eligible=false; ceiling_rate=${ceiling_rate}"
        break
    fi
done
jq -n \
    --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson ceiling_rate_per_second "${ceiling_rate}" \
    --argjson retained_budget_bytes "${RETAINED_BUDGET_BYTES}" \
    --argjson retained_budget_fraction 0.75 \
    --argjson stage_seconds "${STAGE_SECONDS}" \
    --argjson max_retained_records "${MAX_RETAINED_RECORDS}" \
    --argjson shadow_on_failure "${SHADOW_ON_FAILURE}" \
    '{kind:"tier_e_sustained_capacity_pilot", generated_at:$generated_at, loss_free_ceiling_rate_per_second:$ceiling_rate_per_second, retained_budget_bytes:$retained_budget_bytes, retained_budget_fraction:$retained_budget_fraction, stage_seconds:$stage_seconds, max_retained_records:$max_retained_records, shadow_on_failure:$shadow_on_failure}' \
    > "${ARTIFACT_DIR}/capacity-manifest.json"

if [[ "${RUN_DECISION}" == true && "${ceiling_rate}" -gt 0 ]]; then
    for fraction in 50 75 90; do
        rate=$((ceiling_rate * fraction / 100))
        (( rate > 0 )) || continue
        run_stage "${rate}" "${DECISION_STAGE_SECONDS}" "${DECISION_FORKS}" "decision-${fraction}pct"
        eligible_stage "${STAGE_PATH}/results.jsonl" || {
            event "decision-${fraction}pct-rate-${rate}" INCOMPLETE "eligible=false"
            exit 1
        }
        event "decision-${fraction}pct-rate-${rate}" COMPLETED "eligible=true"
    done
fi
if [[ "${ceiling_rate}" -eq 0 ]]; then
    event runner INCOMPLETE "no eligible pilot stage"
    terminal=true
    exit 1
fi
event runner PASSED "loss_free_ceiling_rate=${ceiling_rate}"
terminal=true

#!/usr/bin/env bash
set -euo pipefail

BUILD_DIR=${1:?usage: check_tier_e_benchmark.sh <native-build-dir> [output-file]}
BASELINE_FILE=${2:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/profiler-native/tier-e-benchmark-baseline.env"}
OUTPUT_FILE=${3:-"${BUILD_DIR}/tier-e-benchmark.log"}

set -a
# shellcheck disable=SC1090
source "${BASELINE_FILE}"
set +a

: > "${OUTPUT_FILE}"

check_mode() {
    local mode=$1 prefix=$2
    local mode_log="${OUTPUT_FILE}.${mode}"
    : > "${mode_log}"
    for _ in 1 2 3; do
        ctest --test-dir "${BUILD_DIR}" -V -R "^agent_phase_benchmark_${mode}$" 2>&1 \
            | tee -a "${OUTPUT_FILE}" >> "${mode_log}"
    done
    mapfile -t lines < <(grep "tier-e-benchmark:" "${mode_log}")
    [[ "${#lines[@]}" -eq 3 ]] || { echo "missing ${mode} benchmark output" >&2; return 1; }
    local total=0 name value baseline limit median
    for name in GET_STACK_TRACE_NS RAW_KEY_NS LOGICAL_METADATA_NS; do
        local lower
        lower=$(echo "${name}" | tr '[:upper:]' '[:lower:]')
        local values=()
        for line in "${lines[@]}"; do
            values+=("$(sed -n "s/.*${lower}=\([0-9][0-9]*\).*/\1/p" <<<"${line}")")
        done
        median=$(printf '%s\n' "${values[@]}" | sort -n | sed -n '2p')
        baseline=${prefix}_${name}
        baseline=${!baseline}
        limit=$((baseline * 125 / 100))
        (( median <= limit )) || { echo "${mode} median_${lower}=${median} exceeds ${limit}" >&2; return 1; }
        total=$((total + median))
        echo "tier-e-benchmark-samples: mode=${mode} phase=${lower} values=${values[*]} median=${median} limit=${limit}" \
            | tee -a "${OUTPUT_FILE}"
    done
    echo "tier-e-benchmark-gate: mode=${mode} median_total_ns=${total} limit=125% status=PASS" \
        | tee -a "${OUTPUT_FILE}"
}

check_mode full FULL
check_mode unique UNIQUE

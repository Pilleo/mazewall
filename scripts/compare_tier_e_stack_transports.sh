#!/usr/bin/env bash
set -euo pipefail

BUILD_DIR=${1:?usage: compare_tier_e_stack_transports.sh <native-build-dir> [output-file]}
OUTPUT_FILE=${2:-"${BUILD_DIR}/tier-e-stack-transport-comparison.log"}

: > "${OUTPUT_FILE}"

run_mode() {
    local test_name=$1
    local transport=$2
    local mode=$3
    local log="${OUTPUT_FILE}.${test_name}"
    : > "${log}"
    for _ in 1 2 3; do
        ctest --test-dir "${BUILD_DIR}" -V -R "^${test_name}$" 2>&1 | tee -a "${OUTPUT_FILE}" >> "${log}"
    done
    mapfile -t lines < <(grep 'tier-e-benchmark:' "${log}")
    [[ "${#lines[@]}" -eq 3 ]] || { echo "missing ${test_name} benchmark output" >&2; return 1; }
    local field values median
    for field in get_stack_trace_ns raw_key_ns logical_metadata_ns serialization_ns publication_bytes; do
        values=()
        for line in "${lines[@]}"; do
            values+=("$(sed -n "s/.*${field}=\\([0-9][0-9]*\\).*/\\1/p" <<<"${line}")")
        done
        median=$(printf '%s\n' "${values[@]}" | sort -n | sed -n '2p')
        printf 'tier-e-stack-transport: transport=%s mode=%s metric=%s samples=%s median=%s\n' \
            "${transport}" "${mode}" "${field}" "${values[*]}" "${median}" | tee -a "${OUTPUT_FILE}"
    done
}

run_mode agent_phase_benchmark_full dictionary full-stream
run_mode agent_phase_benchmark_unique dictionary unique-stack-syscall
run_mode agent_phase_benchmark_inline_full inline-per-invocation full-stream
run_mode agent_phase_benchmark_inline_unique inline-per-invocation unique-stack-syscall

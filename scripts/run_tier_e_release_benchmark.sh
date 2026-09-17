#!/usr/bin/env bash
set -euo pipefail

BUILD_DIR=${1:?usage: run_tier_e_release_benchmark.sh <native-build-dir> [output-file]}
OUTPUT_FILE=${2:-"${BUILD_DIR}/tier-e-release-benchmark.log"}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)

cmake -S "${REPOSITORY_DIR}/profiler-native" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release
cmake --build "${BUILD_DIR}" -j2

{
    printf 'tier-e-release-benchmark: revision=%s\n' "$(git -C "${REPOSITORY_DIR}" rev-parse HEAD)"
    printf 'tier-e-release-benchmark: kernel=%s\n' "$(uname -srmo)"
    printf 'tier-e-release-benchmark: compiler=%s\n' "$(c++ --version | head -n1)"
    printf 'tier-e-release-benchmark: jdk=%s\n' "$(java -version 2>&1 | head -n1)"
    printf 'tier-e-release-benchmark: warmups=3 samples=10 build_type=Release\n'
} > "${OUTPUT_FILE}"

summarize_metric() {
    local log_file=$1 scenario=$2 metric=$3
    mapfile -t values < <(sed -n "s/.*${metric}=\\([0-9][0-9]*\\).*/\\1/p" "${log_file}" | tail -n 10 | sort -n)
    [[ "${#values[@]}" -eq 10 ]] || { echo "missing ${metric} samples for ${scenario}" >&2; return 1; }
    printf 'tier-e-release-summary: scenario=%s metric=%s p50=%s p95=%s p99=%s\n' \
        "${scenario}" "${metric}" "${values[4]}" "${values[9]}" "${values[9]}" >> "${OUTPUT_FILE}"
}

run_scenario() {
    local scenario=$1
    local scenario_log="${OUTPUT_FILE}.${scenario}"
    : > "${scenario_log}"
    for _ in 1 2 3; do
        ctest --test-dir "${BUILD_DIR}" -V -R "^${scenario}$" >> "${scenario_log}" 2>&1
    done
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        ctest --test-dir "${BUILD_DIR}" -V -R "^${scenario}$" >> "${scenario_log}" 2>&1
    done
    cat "${scenario_log}" >> "${OUTPUT_FILE}"
    for metric in get_stack_trace_ns raw_key_ns logical_metadata_ns serialization_ns publication_bytes publication_latency_ns lock_wait_ns peak_retained_bytes publication_count coalesced_publications failed_publications; do
        summarize_metric "${scenario_log}" "${scenario}" "${metric}"
    done
}

for scenario in \
    agent_phase_benchmark_full_disabled \
    agent_phase_benchmark_full \
    agent_phase_benchmark_full_live \
    agent_phase_benchmark_unique_disabled \
    agent_phase_benchmark_unique \
    agent_phase_benchmark_unique_live; do
    run_scenario "${scenario}"
done

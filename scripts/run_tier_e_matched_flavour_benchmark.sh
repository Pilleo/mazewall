#!/usr/bin/env bash
set -euo pipefail

BUILD_DIR=${1:?usage: run_tier_e_matched_flavour_benchmark.sh <native-build-dir> [output-file]}
OUTPUT_FILE=${2:-"${BUILD_DIR}/tier-e-matched-flavour-benchmark.log"}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)
SCENARIOS=(
    agent_matched_benchmark_none
    agent_matched_benchmark_dictionary_full
    agent_matched_benchmark_dictionary_unique
    agent_matched_benchmark_inline_full
    agent_matched_benchmark_inline_unique
)

cmake -S "${REPOSITORY_DIR}/profiler-native" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release
cmake --build "${BUILD_DIR}" -j2
{
    printf 'tier-e-matched-benchmark: mode=native-agent-only forks=5 warmups=3 batches=10 iterations=100\n'
    printf 'tier-e-matched-benchmark: revision=%s\n' "$(git -C "${REPOSITORY_DIR}" rev-parse HEAD)"
    printf 'tier-e-matched-benchmark: kernel=%s\n' "$(uname -srmo)"
    printf 'tier-e-matched-benchmark: jdk=%s\n' "$(java -version 2>&1 | head -n1)"
} > "${OUTPUT_FILE}"

for fork in 0 1 2 3 4; do
    for offset in 0 1 2 3 4; do
        index=$(((fork + offset) % 5))
        scenario=${SCENARIOS[${index}]}
        ctest --test-dir "${BUILD_DIR}" -V -R "^${scenario}$" >> "${OUTPUT_FILE}" 2>&1
    done
done

for scenario in "${SCENARIOS[@]}"; do
    mapfile -t values < <(awk -v target="${scenario}" '
        $0 ~ "Start [0-9]+: " target { active=1 }
        active && /agent-workload-batch:/ { sub(/.*durationNs=/, ""); print $1 }
        active && /Test #[0-9]+: / { active=0 }
    ' "${OUTPUT_FILE}" | sort -n)
    [[ "${#values[@]}" -eq 50 ]] || { echo "missing matched samples for ${scenario}" >&2; exit 1; }
    printf 'tier-e-matched-summary: scenario=%s p50_ns=%s p95_ns=%s p99_ns=%s samples=%s\n' \
        "${scenario}" "${values[24]}" "${values[47]}" "${values[49]}" "${#values[@]}" >> "${OUTPUT_FILE}"
done

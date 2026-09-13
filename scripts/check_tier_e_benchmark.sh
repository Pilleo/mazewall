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
ctest --test-dir "${BUILD_DIR}" -V -R 'agent_phase_benchmark_(full|unique)' 2>&1 | tee "${OUTPUT_FILE}"

check_mode() {
    local mode=$1 prefix=$2 line
    line=$(grep "tier-e-benchmark:" "${OUTPUT_FILE}" | grep "mode=${mode}" -m1 || true)
    if [[ -z "${line}" ]]; then
        # CTest does not repeat the mode in the benchmark line; select by order.
        local index=$([[ "${mode}" == full ]] && echo 1 || echo 2)
        line=$(grep "tier-e-benchmark:" "${OUTPUT_FILE}" | sed -n "${index}p")
    fi
    [[ -n "${line}" ]] || { echo "missing ${mode} benchmark output" >&2; return 1; }
    local total=0 name value baseline limit
    for name in GET_STACK_TRACE_NS RAW_KEY_NS LOGICAL_METADATA_NS; do
        local lower
        lower=$(echo "${name}" | tr '[:upper:]' '[:lower:]')
        value=$(sed -n "s/.*${lower}=\([0-9][0-9]*\).*/\1/p" <<<"${line}")
        baseline=${prefix}_${name}
        baseline=${!baseline}
        limit=$((baseline * 125 / 100))
        (( value <= limit )) || { echo "${mode} ${lower}=${value} exceeds ${limit}" >&2; return 1; }
        total=$((total + value))
    done
    echo "tier-e-benchmark-gate: mode=${mode} total_ns=${total} limit=125% status=PASS"
}

check_mode full FULL
check_mode unique UNIQUE

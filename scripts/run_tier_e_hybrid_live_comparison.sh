#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-"${ROOT}/build/tier-e-hybrid-live-comparison"}
FORKS=${TIER_E_HYBRID_LIVE_FORKS:-5}
ITERATIONS=${TIER_E_HYBRID_LIVE_ITERATIONS:-200}
RUN_ID="tier-e-live-$$"
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
mkdir -p "${OUT}"
cleanup() {
    "${TIER_E_CONTAINER_ENGINE:-docker}" ps -q --filter "name=${RUN_ID}" 2>/dev/null |
        xargs -r "${TIER_E_CONTAINER_ENGINE:-docker}" rm -f >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
"${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" > "${OUT}/source-state-before.json" || exit 1
sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
    "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUT}/bundle-sha256.txt" || exit 1
git -C "${ROOT}" diff --binary | sha256sum | awk '{print $1}' > "${OUT}/source-diff-sha256.txt"
uname -a > "${OUT}/kernel.txt"
printf '{"schema_version":1,"forks":%s,"warmups":3,"batches":10,"iterations":%s,"profiles":["syscall_dense","stack_diverse","class_churn"],"marker_version":"v2","recovery_mode":"recover","live_interval_ms":100,"policy_eligibility":false}\n' \
    "${FORKS}" "${ITERATIONS}" > "${OUT}/run-manifest.json"
: > "${OUT}/fork-results.jsonl"
failed=0
for ((fork=0;fork<FORKS;fork++)); do
    for profile in syscall_dense stack_diverse class_churn; do
        for emission in full-stream unique-stack-syscall; do
            if (( fork % 2 == 0 )); then publications=(0 1); else publications=(1 0); fi
            for live in "${publications[@]}"; do
                mode=dictionary-full-stream
                [[ "${emission}" == unique-stack-syscall ]] && mode=dictionary-unique-edge
                dir="${OUT}/fork-${fork}-${profile}-${mode}-live-${live}"
                mkdir -p "${dir}"
                printf 'instrumented=1\n' > "${dir}/instrumented.env"
                status=0
                timeout --signal=TERM --kill-after=30s 600s env \
                    TIER_E_SPLIT_RUN_ID="${RUN_ID}-fork-${fork}-${profile}-${mode}-live-${live}" \
                    TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_BENCHMARK=1 TIER_E_SPLIT_BENCHMARK_PHASES=1 \
                    TIER_E_SPLIT_LIVE_DEFINITIONS="${live}" TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS="${live}" \
                    TIER_E_SPLIT_MARKER_VERSION=v2 TIER_E_SPLIT_RECOVERY_MODE=recover \
                    TIER_E_SPLIT_BENCH_ITERATIONS="${ITERATIONS}" TIER_E_SPLIT_WORKLOAD_PROFILE="${profile}" \
                    TIER_E_SPLIT_STACK_TRANSPORT=dictionary TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                    TIER_E_SPLIT_ARTIFACT_DIR="${dir}" \
                    bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${dir}/fixture.log" 2>&1 || status=$?
                if [[ -f "${dir}/benchmark-operations.json" && -f "${dir}/terminal-report.json" ]]; then
                    bash "${ROOT}/scripts/tier_e_tools" verify-operations \
                        "${dir}/benchmark-oracle.json" "${dir}/benchmark-operations.json" \
                        "${dir}/terminal-report.json" "${dir}/operation-verdict.json" \
                        > "${dir}/operation-verifier.log" 2>&1 || true
                fi
                bash "${ROOT}/scripts/tier_e_tools" live-fork-row "${dir}" "${fork}" "${mode}" "${profile}" \
                    "${live}" "${status}" "${OUT}/fork-results.jsonl" "$((ITERATIONS * 10))" || failed=$((failed+1))
            done
        done
    done
done
source_changed=0
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" --verify "${OUT}/source-state-before.json" > "${OUT}/source-state-after.json" || source_changed=1
bash "${ROOT}/scripts/tier_e_tools" live-matrix-summary "${OUT}" "${failed}" "${source_changed}"

#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-"${ROOT}/build/tier-e-hybrid-fault-matrix"}
FORKS=${TIER_E_HYBRID_FAULT_FORKS:-15}
OPERATIONS=${TIER_E_HYBRID_FAULT_OPERATIONS:-72}
BACKEND=${TIER_E_HYBRID_FAULT_BACKEND:-uprobe}
LIVE=${TIER_E_HYBRID_FAULT_LIVE:-0}
PHASES=${TIER_E_HYBRID_FAULT_PHASES:-0}
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
mkdir -p "${OUT}"
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${OPERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
[[ "${BACKEND}" == uprobe || "${BACKEND}" == usdt ]] || exit 2
[[ "${LIVE}" == 0 || "${LIVE}" == 1 ]] || exit 2
"${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" > "${OUT}/source-state-before.json" || exit 1
sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
    "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUT}/bundle-sha256.txt" || exit 1
git -C "${ROOT}" diff --binary | sha256sum | awk '{print $1}' > "${OUT}/source-diff-sha256.txt"
printf '{"schema_version":2,"forks":%s,"operations":%s,"seed_start":23063,"marker":"v2","backend":"%s","fault_syscall":257,"native_live_snapshots":%s,"phase_instrumentation":%s,"policy_eligibility":false}\n' \
    "${FORKS}" "${OPERATIONS}" "${BACKEND}" "${LIVE}" "${PHASES}" > "${OUT}/run-manifest.json"
: > "${OUT}/fork-results.jsonl"
failures=0
for ((fork=0;fork<FORKS;fork++)); do
    for emission in full-stream unique-stack-syscall; do
        dir="${OUT}/fork-${fork}-${emission}"
        mkdir -p "${dir}"
        status=0
        timeout --signal=TERM --kill-after=30s 600s env \
            TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_MARKER_VERSION=v2 \
            TIER_E_SPLIT_MARKER_BACKEND="${BACKEND}" TIER_E_SPLIT_RECOVERY_MODE=recover \
            TIER_E_SPLIT_LIVE_DEFINITIONS="${LIVE}" TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS="${LIVE}" \
            TIER_E_SPLIT_BENCHMARK_PHASES="${PHASES}" \
            TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL=257 TIER_E_SPLIT_COVERAGE=1 \
            TIER_E_SPLIT_COVERAGE_OPERATIONS="${OPERATIONS}" \
            TIER_E_COVERAGE_SEED="$((23063+fork))" \
            TIER_E_SPLIT_EMISSION_MODE="${emission}" TIER_E_SPLIT_ARTIFACT_DIR="${dir}" \
            bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${dir}/fixture.log" 2>&1 || status=$?
        bash "${ROOT}/scripts/tier_e_tools" fault-fork-row "${dir}" "${fork}" "${emission}" "${status}" "${OUT}/fork-results.jsonl"
        [[ $? == 0 ]] || failures=$((failures+1))
    done
done
source_changed=0
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" --verify "${OUT}/source-state-before.json" > "${OUT}/source-state-after.json" || source_changed=1
bash "${ROOT}/scripts/tier_e_tools" fault-matrix-summary "${OUT}" "${failures}" "${source_changed}"

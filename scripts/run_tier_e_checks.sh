#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LEVEL=${1:-focused}

if [[ "${LEVEL}" == "--help" || "${LEVEL}" == "-h" ]]; then
    cat <<'EOF'
Usage: scripts/run_tier_e_checks.sh <focused|native|kernel-smoke|delivery>

focused      Cached Kotlin checks for the current Tier E benchmark and lifecycle paths.
native       Deterministic native CTest fixture suite in a persistent build directory.
kernel-smoke One rootful Docker Tier E smoke matrix; requires Docker daemon access.
delivery     Full profiler suite, native fixtures, and backlog validation.
EOF
    exit 0
fi

case "${LEVEL}" in
    focused|native|kernel-smoke|delivery) ;;
    *)
        echo "unknown Tier E check level: ${LEVEL}" >&2
        exit 2
        ;;
esac

RUN_ID=${MAZEWALL_TIER_E_CHECK_RUN_ID:-"$(date -u +%Y%m%dT%H%M%SZ)-$$"}
ARTIFACT_DIR=${MAZEWALL_TIER_E_CHECK_ARTIFACT_DIR:-"${REPOSITORY_DIR}/build/tier-e-checks/${RUN_ID}"}
mkdir -p "${ARTIFACT_DIR}"

stage() {
    local name=$1
    shift
    local log="${ARTIFACT_DIR}/${name}.log"
    printf 'tier-e-check: stage=%s state=started timestamp=%s\n' "${name}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "${ARTIFACT_DIR}/events.log"
    "$@" >"${log}" 2>&1
    printf 'tier-e-check: stage=%s state=completed timestamp=%s log=%s\n' "${name}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${log}" | tee -a "${ARTIFACT_DIR}/events.log"
}

native_checks() {
    local build_dir="${REPOSITORY_DIR}/build/tier-e-native-check"
    stage native-configure cmake -S "${REPOSITORY_DIR}/profiler-native" -B "${build_dir}" -DCMAKE_BUILD_TYPE=Release
    stage native-build cmake --build "${build_dir}" -j2
    stage native-ctest ctest --test-dir "${build_dir}" --output-on-failure
}

case "${LEVEL}" in
    focused)
        stage focused-gradle "${REPOSITORY_DIR}/gradlew" :profiler:test \
            --tests io.mazewall.profiler.tierE.stress.TierEMatchedBenchmarkJsonTest \
            --tests io.mazewall.profiler.tierE.stress.TierECommonFileWorkloadTest \
            --tests io.mazewall.profiler.internal.attribution.TierEProfilerSessionLifecycleTest
        ;;
    native)
        native_checks
        ;;
    kernel-smoke)
        stage kernel-smoke env \
            MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-capture-profile \
            MAZEWALL_TIER_E_FORKS=1 \
            MAZEWALL_TIER_E_WARMUPS=0 \
            MAZEWALL_TIER_E_BATCHES=1 \
            MAZEWALL_TIER_E_ITERATIONS=64 \
            bash "${REPOSITORY_DIR}/scripts/run_tier_e_trustworthy_benchmark.sh" "${ARTIFACT_DIR}/kernel-smoke"
        ;;
    delivery)
        stage profiler-test "${REPOSITORY_DIR}/gradlew" :profiler:test
        native_checks
        stage backlog-check "${REPOSITORY_DIR}/scripts/adkw" check-backlog
        ;;
esac

printf 'tier-e-check: level=%s state=passed artifacts=%s\n' "${LEVEL}" "${ARTIFACT_DIR}" | tee -a "${ARTIFACT_DIR}/events.log"

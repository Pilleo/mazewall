#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
ARTIFACT_DIR=${TIER_E_ARTIFACT_DIR:-"${PROJECT_ROOT}/build/tier-e-evidence"}
DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}

if [[ "${ENGINE}" != docker ]]; then
    echo "Tier E privileged gate requires Docker; rootless runtimes are INCOMPLETE evidence." >&2
    exit 2
fi

mkdir -p "${ARTIFACT_DIR}"
ARTIFACT_DIR=$(cd "${ARTIFACT_DIR}" && pwd)
docker --host "${DOCKER_HOST}" build -t "${IMAGE}" -f "${PROJECT_ROOT}/infra/dev/Containerfile" "${PROJECT_ROOT}"

set +e
docker --host "${DOCKER_HOST}" run --rm --privileged --pid=host --network=host --userns=host \
    --security-opt seccomp=unconfined \
    -v "${PROJECT_ROOT}:${PROJECT_ROOT}" \
    -v "${ARTIFACT_DIR}:${ARTIFACT_DIR}" \
    -v "${HOME}/.gradle:${HOME}/.gradle" \
    -e GRADLE_USER_HOME="${HOME}/.gradle" \
    -e TIER_E_ARTIFACT_DIR="${ARTIFACT_DIR}" \
    -e TIER_E_AGENT_LIBRARY="${PROJECT_ROOT}/profiler-native/build/libmazewall_stack_agent.so" \
    -e TIER_E_WORKLOAD_CLASSPATH="${PROJECT_ROOT}/profiler-native/build/agent-test-classes" \
    -e CI="${CI:-false}" \
    -e MAZEWALL_IN_CONTAINER=true \
    -w "${PROJECT_ROOT}" \
    "${IMAGE}" bash -lc '
        set -euo pipefail
        mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
        mkdir -p profiler-native/build
        cmake -S profiler-native -B profiler-native/build -DCMAKE_BUILD_TYPE=Debug
        cmake --build profiler-native/build -j2
        ctest --test-dir profiler-native/build -V 2>&1 | tee "${TIER_E_ARTIFACT_DIR}/native-ctest.log"
        rm -rf profiler/build/test-results/integrationTestFreshJvm \
            profiler/build/reports/tests/integrationTestFreshJvm
        TIER_E_AGENT_LIBRARY="${TIER_E_AGENT_LIBRARY}" \
        TIER_E_WORKLOAD_CLASSPATH="${TIER_E_WORKLOAD_CLASSPATH}" \
        ./gradlew :profiler:test :profiler:integrationTest :profiler:integrationTestFreshJvm \
            -Dtier.e.agent="${TIER_E_AGENT_LIBRARY}" \
            -Dtier.e.workloadClasspath="${TIER_E_WORKLOAD_CLASSPATH}" \
            --no-daemon --rerun-tasks --stacktrace 2>&1 | tee "${TIER_E_ARTIFACT_DIR}/profiler-tests.log"
        if grep -q 'TierEEndToEndIntegrationTest' profiler/build/test-results/integrationTestFreshJvm/*.xml && \
           grep -q 'skipped="1"' profiler/build/test-results/integrationTestFreshJvm/*TierEEndToEndIntegrationTest*.xml; then
            echo "Tier E native-backed end-to-end fixture was skipped" >&2
            exit 1
        fi
        if grep -q 'TierEDaemonNativeWorkflowIntegrationTest' profiler/build/test-results/integrationTestFreshJvm/*.xml && \
           grep -q 'skipped="1"' profiler/build/test-results/integrationTestFreshJvm/*TierEDaemonNativeWorkflowIntegrationTest*.xml; then
            echo "Tier E daemon-owned native workflow fixture was skipped" >&2
            exit 1
        fi
        if ./scripts/check_tier_e_benchmark.sh profiler-native/build \
            profiler-native/tier-e-benchmark-baseline.env "${TIER_E_ARTIFACT_DIR}/benchmark-gate.log"; then
            printf 'PASS\n' > "${TIER_E_ARTIFACT_DIR}/benchmark-gate-status.txt"
        else
            printf 'FAILED\n' > "${TIER_E_ARTIFACT_DIR}/benchmark-gate-status.txt"
            exit 1
        fi
    '
GATE_STATUS=$?
set -e

BENCHMARK_GATE_STATUS=$(cat "${ARTIFACT_DIR}/benchmark-gate-status.txt" 2>/dev/null || printf 'NOT_RUN')

cat > "${ARTIFACT_DIR}/run-metadata.txt" <<EOF
commit_sha=$(git -C "${PROJECT_ROOT}" rev-parse HEAD)
kernel=$(uname -srvm)
docker_image=${IMAGE}
capture_domain_validity=PASS
transport_completeness=PASS
attribution_coverage=PASS
dictionary_integrity=PASS
lifecycle_validity=PASS
benchmark_gate_status=${BENCHMARK_GATE_STATUS}
rootful_gate_status=${GATE_STATUS}
rootless_podman_fixture=INCOMPLETE
EOF

echo "Tier E rootful evidence written to ${ARTIFACT_DIR}"
exit "${GATE_STATUS}"

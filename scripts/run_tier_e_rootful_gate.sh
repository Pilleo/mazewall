#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
ARTIFACT_DIR=${TIER_E_ARTIFACT_DIR:-"${PROJECT_ROOT}/build/tier-e-evidence"}
DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}

case "${ENGINE}" in
    docker)
        ENGINE_CMD=(docker --host "${DOCKER_HOST}")
        security_options=$("${ENGINE_CMD[@]}" info --format '{{json .SecurityOptions}}') || exit 2
        [[ "${security_options}" == \[*\] && "${security_options}" != *rootless* ]] || {
            echo 'Tier E privileged gate requires a rootful Docker server' >&2; exit 2;
        }
        ;;
    podman)
        ENGINE_CMD=(podman)
        if [[ -n "${MAZEWALL_CONTAINER_HOST:-}" ]]; then
            ENGINE_CMD=(podman --url "${MAZEWALL_CONTAINER_HOST}")
        fi
        [[ "$("${ENGINE_CMD[@]}" info --format '{{.Host.Security.Rootless}}')" == false ]] || {
            echo 'Tier E privileged gate requires a rootful Podman server' >&2; exit 2;
        }
        ;;
    *) echo "Unsupported TIER_E_CONTAINER_ENGINE: ${ENGINE}" >&2; exit 2 ;;
esac

mkdir -p "${ARTIFACT_DIR}"
ARTIFACT_DIR=$(cd "${ARTIFACT_DIR}" && pwd)
if ! prior_artifact=$(find "${ARTIFACT_DIR}" -mindepth 1 -maxdepth 1 -print -quit); then
    echo "Unable to inspect Tier E artifact directory: ${ARTIFACT_DIR}" >&2
    exit 1
fi
[[ -z "${prior_artifact}" ]] || {
    echo "Tier E gate requires a fresh artifact directory; existing entry: ${prior_artifact}" >&2
    exit 1
}
CONTAINERFILE_SHA=$(sha256sum "${PROJECT_ROOT}/infra/dev/Containerfile" | cut -d' ' -f1)
image_sha=$("${ENGINE_CMD[@]}" image inspect "${IMAGE}" \
    --format '{{index .Config.Labels "io.mazewall.containerfile.sha256"}}' 2>/dev/null || true)
if [[ "${image_sha}" != "${CONTAINERFILE_SHA}" ]]; then
    "${ENGINE_CMD[@]}" build --label "io.mazewall.containerfile.sha256=${CONTAINERFILE_SHA}" \
        -t "${IMAGE}" -f "${PROJECT_ROOT}/infra/dev/Containerfile" "${PROJECT_ROOT}"
fi

ENGINE=("${ENGINE_CMD[@]}")
RUN_ID="tier-e-rootful-gate-$(id -u)-$(date +%s%N)-$$"
WORKSPACE_VOLUME="${RUN_ID}-workspace"
TOOLCHAIN_KEY=$(sha256sum "${PROJECT_ROOT}/gradle/wrapper/gradle-wrapper.properties" \
    "${PROJECT_ROOT}/infra/dev/Containerfile" | sha256sum | cut -c1-12)
CACHE_VOLUME="mazewall-tier-e-rootful-gradle-jdk25-$(id -u)-${TOOLCHAIN_KEY}"
CONTAINER="${RUN_ID}-runner"
created_container=false
created_workspace=false
exported=false
export_artifacts() {
    local stage unsafe
    stage=$(mktemp -d "${ARTIFACT_DIR}/.tier-e-export.XXXXXX") || return 1
    if ! "${ENGINE[@]}" cp "${CONTAINER}:/artifacts/." "${stage}/" 2>/dev/null; then
        echo "Tier E export copy failed; staging retained at ${stage}" >&2
        return 1
    fi
    if ! unsafe=$(find "${stage}" \( -type l -o \( ! -type f -a ! -type d \) \) -print -quit); then
        echo "Tier E export validation failed; staging retained at ${stage}" >&2
        return 1
    fi
    if [[ -n "${unsafe}" ]]; then
        echo "Tier E export contains an unsupported file type or symlink; staging retained at ${stage}" >&2
        return 1
    fi
    if ! cp -a "${stage}/." "${ARTIFACT_DIR}/"; then
        echo "Tier E host publication failed; staging retained at ${stage}" >&2
        return 1
    fi
    if ! rm -rf "${stage}"; then
        echo "Tier E export staging cleanup failed: ${stage}" >&2
        return 1
    fi
    exported=true
    return 0
}
cleanup() {
    local status=$?
    local retain=false
    trap - EXIT
    if [[ "${created_container}" == true ]]; then
        if ! "${ENGINE[@]}" logs "${CONTAINER}" > "${ARTIFACT_DIR}/rootful-gate.log" 2>&1; then
            echo "Tier E logs unavailable; preserving ${CONTAINER}" >&2
            status=1
            retain=true
        fi
        if [[ "${exported}" != true ]] && ! export_artifacts; then
            echo "Tier E artifact export failed; preserving ${CONTAINER} and ${WORKSPACE_VOLUME}" >&2
            status=1
            retain=true
        fi
        if [[ "${retain}" == true ]]; then
            "${ENGINE[@]}" stop -t 2 "${CONTAINER}" >/dev/null 2>&1 ||
                "${ENGINE[@]}" kill "${CONTAINER}" >/dev/null 2>&1 || status=1
            echo "Recovery: ${ENGINE[*]} cp ${CONTAINER}:/artifacts/. ${ARTIFACT_DIR}/" >&2
        else
            "${ENGINE[@]}" rm -f "${CONTAINER}" >/dev/null 2>&1 || status=1
        fi
    fi
    if [[ "${created_workspace}" == true && "${retain}" != true ]]; then
        "${ENGINE[@]}" volume rm "${WORKSPACE_VOLUME}" >/dev/null 2>&1 || status=1
    elif [[ "${created_workspace}" == true ]]; then
        echo "Recovery workspace retained: ${WORKSPACE_VOLUME}" >&2
    fi
    if [[ "${status}" -ne 0 && -f "${ARTIFACT_DIR}/run-metadata.txt" ]]; then
        sed -i -E \
            -e "s/^rootful_gate_status=.*/rootful_gate_status=${status}/" \
            -e 's/^(capture_domain_validity|transport_completeness|attribution_coverage|dictionary_integrity|lifecycle_validity)=PASS/\1=INCOMPLETE/' \
            "${ARTIFACT_DIR}/run-metadata.txt" || status=1
    fi
    exit "${status}"
}
trap cleanup EXIT
if "${ENGINE[@]}" volume inspect "${WORKSPACE_VOLUME}" >/dev/null 2>&1; then
    echo "Refusing to reuse a prior Tier E workspace: ${WORKSPACE_VOLUME}" >&2
    exit 1
fi
"${ENGINE[@]}" volume create "${WORKSPACE_VOLUME}" >/dev/null
created_workspace=true
"${ENGINE[@]}" volume create "${CACHE_VOLUME}" >/dev/null
# Send the caller's tracked and untracked source files, including local edits,
# through the engine API. The privileged build has no writable checkout mount.
(cd "${PROJECT_ROOT}" && git ls-files -z --cached --others --exclude-standard -- \
    gradle buildSrc enforcer platform profiler profiler-native portals demos infra config scripts src docs \
    gradlew build.gradle.kts settings.gradle.kts gradle.properties) |
    tar -C "${PROJECT_ROOT}" -cf - --null -T - |
    "${ENGINE[@]}" run --rm -i -v "${WORKSPACE_VOLUME}:/workspace" --entrypoint tar "${IMAGE}" -C /workspace -xf -

"${ENGINE[@]}" create --name "${CONTAINER}" --privileged --pid=host --network=host --userns=host \
    --security-opt seccomp=unconfined \
    -v "${WORKSPACE_VOLUME}:/workspace" \
    -v "${CACHE_VOLUME}:/container-gradle-home" \
    -e GRADLE_USER_HOME=/container-gradle-home \
    -e TIER_E_ARTIFACT_DIR=/artifacts \
    -e TIER_E_AGENT_LIBRARY=/workspace/profiler-native/build/libmazewall_stack_agent.so \
    -e TIER_E_WORKLOAD_CLASSPATH=/workspace/profiler-native/build/agent-test-classes \
    -e CI="${CI:-false}" \
    -e MAZEWALL_IN_CONTAINER=true \
    -w /workspace \
    "${IMAGE}" bash -lc '
        set -euo pipefail
        mkdir -p /artifacts
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
    ' >/dev/null
created_container=true
set +e
"${ENGINE[@]}" start -a "${CONTAINER}"
GATE_STATUS=$?
set -e
# Export before parsing status: the container can fail halfway through work.
"${ENGINE[@]}" logs "${CONTAINER}" > "${ARTIFACT_DIR}/rootful-gate.log" 2>&1 || true
export_artifacts || GATE_STATUS=1

BENCHMARK_GATE_STATUS=$(cat "${ARTIFACT_DIR}/benchmark-gate-status.txt" 2>/dev/null || printf 'NOT_RUN')
if [[ "${GATE_STATUS}" -eq 0 && "${BENCHMARK_GATE_STATUS}" != PASS ]]; then
    echo "Tier E benchmark gate has no passing artifact: ${BENCHMARK_GATE_STATUS}" >&2
    GATE_STATUS=1
fi
if [[ "${GATE_STATUS}" -eq 0 ]]; then
    DIMENSION_STATUS=PASS
else
    DIMENSION_STATUS=INCOMPLETE
fi

cat > "${ARTIFACT_DIR}/run-metadata.txt" <<EOF
commit_sha=$(git -C "${PROJECT_ROOT}" rev-parse HEAD)
kernel=$("${ENGINE[@]}" info --format "$([[ "${TIER_E_CONTAINER_ENGINE:-docker}" == podman ]] && printf '{{.Host.Kernel}}' || printf '{{.KernelVersion}}')")
docker_image=${IMAGE}
capture_domain_validity=${DIMENSION_STATUS}
transport_completeness=${DIMENSION_STATUS}
attribution_coverage=${DIMENSION_STATUS}
dictionary_integrity=${DIMENSION_STATUS}
lifecycle_validity=${DIMENSION_STATUS}
benchmark_gate_status=${BENCHMARK_GATE_STATUS}
rootful_gate_status=${GATE_STATUS}
rootless_podman_fixture=INCOMPLETE
EOF

echo "Tier E rootful evidence written to ${ARTIFACT_DIR}"
exit "${GATE_STATUS}"

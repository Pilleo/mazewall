#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE=${MAZEWALL_TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:perf-20260916}
ARTIFACT_DIR=${1:-"${REPOSITORY_DIR}/build/tier-e-trustworthy-benchmark"}
mkdir -p "${ARTIFACT_DIR}"
mkdir -p "${ARTIFACT_DIR}/gradle-home"

# A pair of sibling hyperthreads shares execution resources and biases a
# collector/workload comparison.  Select two distinct physical cores unless an
# operator explicitly supplies the pinned CPU set.
if [[ -n "${MAZEWALL_TIER_E_BENCHMARK_CPUS:-}" ]]; then
    CPU_SET=${MAZEWALL_TIER_E_BENCHMARK_CPUS}
else
    mapfile -t benchmark_cpus < <(lscpu -p=CPU,CORE,SOCKET | awk -F, '$1 !~ /^#/ { key = $2 "," $3; if (!(key in seen)) { seen[key] = 1; print $1 } }' | head -2)
    if [[ ${#benchmark_cpus[@]} -ne 2 ]]; then
        echo "Unable to select two distinct physical CPUs; set MAZEWALL_TIER_E_BENCHMARK_CPUS" >&2
        exit 2
    fi
    CPU_SET="${benchmark_cpus[0]},${benchmark_cpus[1]}"
fi
IFS=, read -r WORKLOAD_CPU COLLECTOR_CPU <<< "${CPU_SET}"
CALLER_UID=$(id -u)
CALLER_GID=$(id -g)
CANONICAL_CACHE=${MAZEWALL_TIER_E_CANONICAL_CACHE:-enabled}
CAPTURE_PROFILE=${MAZEWALL_TIER_E_CAPTURE_PROFILE:-}
BENCHMARK_SCOPE=${MAZEWALL_TIER_E_BENCHMARK_SCOPE:-full}
BENCHMARK_WORKLOAD=${MAZEWALL_TIER_E_BENCHMARK_WORKLOAD:-oracle}
CHILD_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_CHILD_TIMEOUT_SECONDS:-180}
USER_NOTIF_WORKER_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_USER_NOTIF_WORKER_TIMEOUT_SECONDS:-480}
USER_NOTIF_CHILD_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS:-540}
ITERATIONS=${MAZEWALL_TIER_E_ITERATIONS:-100}
WARMUPS=${MAZEWALL_TIER_E_WARMUPS:-3}
BATCHES=${MAZEWALL_TIER_E_BATCHES:-10}
FORKS=${MAZEWALL_TIER_E_FORKS:-5}
SOURCE_REVISION=$(git -C "${REPOSITORY_DIR}" rev-parse HEAD)
SOURCE_DIRTY=$(git -C "${REPOSITORY_DIR}" status --porcelain --untracked-files=no | wc -l | tr -d ' ')
SOURCE_FINGERPRINT=$(git -C "${REPOSITORY_DIR}" diff --no-ext-diff HEAD | sha256sum | awk '{print $1}')
IMAGE_ID=$(docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" image inspect --format '{{.Id}}' "${IMAGE}" 2>/dev/null || printf 'unknown')
KERNEL_RELEASE=$(uname -r)
CPU_TOPOLOGY_FILE="${ARTIFACT_DIR}/cpu-topology.csv"
lscpu -p=CPU,CORE,SOCKET > "${CPU_TOPOLOGY_FILE}"
if [[ "${CANONICAL_CACHE}" != "enabled" && "${CANONICAL_CACHE}" != "disabled" ]]; then
    echo "MAZEWALL_TIER_E_CANONICAL_CACHE must be enabled or disabled" >&2
    exit 2
fi
if [[ -n "${CAPTURE_PROFILE}" && "${CAPTURE_PROFILE}" != "jdk25-unix-openat0" ]]; then
    echo "MAZEWALL_TIER_E_CAPTURE_PROFILE must be empty or jdk25-unix-openat0" >&2
    exit 2
fi
if [[ "${BENCHMARK_WORKLOAD}" != "application" && "${BENCHMARK_WORKLOAD}" != "oracle" ]]; then
    echo "MAZEWALL_TIER_E_BENCHMARK_WORKLOAD must be application or oracle" >&2
    exit 2
fi
if [[ "${BENCHMARK_SCOPE}" != "full" && "${BENCHMARK_SCOPE}" != "tier-e-cache-ab" && "${BENCHMARK_SCOPE}" != "tier-e-cache-paired" && "${BENCHMARK_SCOPE}" != "tier-e-publication" && "${BENCHMARK_SCOPE}" != "tier-e-capture-profile" ]]; then
    echo "MAZEWALL_TIER_E_BENCHMARK_SCOPE must be full, tier-e-cache-ab, tier-e-cache-paired, tier-e-publication, or tier-e-capture-profile" >&2
    exit 2
fi
if [[ "${BENCHMARK_SCOPE}" == "tier-e-capture-profile" && -n "${CAPTURE_PROFILE}" ]]; then
    echo "tier-e-capture-profile selects full and jdk25-unix-openat0 internally; leave MAZEWALL_TIER_E_CAPTURE_PROFILE empty" >&2
    exit 2
fi
if ! [[ "${ITERATIONS}" =~ ^[1-9][0-9]*$ && "${WARMUPS}" =~ ^[0-9]+$ && "${BATCHES}" =~ ^[1-9][0-9]*$ && "${FORKS}" =~ ^[1-9][0-9]*$ && "${CHILD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ && "${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ && "${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    echo "benchmark dimensions and deadlines must be non-negative/positive integers" >&2
    exit 2
fi
if (( USER_NOTIF_CHILD_TIMEOUT_SECONDS <= USER_NOTIF_WORKER_TIMEOUT_SECONDS )); then
    echo "MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS must exceed the worker timeout" >&2
    exit 2
fi

docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --privileged --pid=host --userns=host --cpuset-cpus="${CPU_SET}" --security-opt seccomp=unconfined \
    --user "${CALLER_UID}:${CALLER_GID}" -e GRADLE_USER_HOME=/gradle-cache \
    -e "WORKLOAD_CPU=${WORKLOAD_CPU}" -e "COLLECTOR_CPU=${COLLECTOR_CPU}" \
    -e "CAPTURE_PROFILE=${CAPTURE_PROFILE}" \
    -v "${REPOSITORY_DIR}:${REPOSITORY_DIR}" -v "${ARTIFACT_DIR}:/artifacts" -v "${ARTIFACT_DIR}/gradle-home:/gradle-cache" -w "${REPOSITORY_DIR}" "${IMAGE}" bash -lc '
        set -euo pipefail
        {
            native_build_dir=$(mktemp -d /tmp/jseccomp-tier-e-native-build.XXXXXX)
            cmake -S profiler-native -B "${native_build_dir}" -DCMAKE_BUILD_TYPE=Release
            cmake --build "${native_build_dir}" -j2
            cp "${native_build_dir}/libmazewall_stack_agent.so" /artifacts/libmazewall_stack_agent.so
            ./gradlew --no-daemon --rerun-tasks :profiler:test --tests io.mazewall.profiler.tierE.stress.TierECommonFileWorkloadTest :profiler:installDist
        } 2>&1 | tee /artifacts/build.log
    '

AGENT_SHA256=$(sha256sum "${ARTIFACT_DIR}/libmazewall_stack_agent.so" | awk '{print $1}')
jq -n \
    --arg source_revision "${SOURCE_REVISION}" \
    --arg source_dirty_count "${SOURCE_DIRTY}" \
    --arg source_fingerprint "${SOURCE_FINGERPRINT}" \
    --arg image "${IMAGE}" \
    --arg image_id "${IMAGE_ID}" \
    --arg agent_sha256 "${AGENT_SHA256}" \
    --arg kernel_release "${KERNEL_RELEASE}" \
    --arg cpu_set "${CPU_SET}" \
    --arg workload_cpu "${WORKLOAD_CPU}" \
    --arg collector_cpu "${COLLECTOR_CPU}" \
    --arg scope "${BENCHMARK_SCOPE}" \
    --arg workload "${BENCHMARK_WORKLOAD}" \
    --argjson iterations "${ITERATIONS}" \
    --argjson warmups "${WARMUPS}" \
    --argjson batches "${BATCHES}" \
    --argjson forks "${FORKS}" \
    --argjson child_timeout_seconds "${CHILD_TIMEOUT_SECONDS}" \
    --argjson user_notif_worker_timeout_seconds "${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" \
    --argjson user_notif_child_timeout_seconds "${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" \
    '{schema_version: 1, source_revision: $source_revision, source_dirty_count: $source_dirty_count, source_fingerprint: $source_fingerprint, image: $image, image_id: $image_id, agent_sha256: $agent_sha256, kernel_release: $kernel_release, cpu_set: $cpu_set, workload_cpu: $workload_cpu, collector_cpu: $collector_cpu, scope: $scope, workload: $workload, iterations: $iterations, warmups: $warmups, batches: $batches, forks: $forks, child_timeout_seconds: $child_timeout_seconds, user_notif_worker_timeout_seconds: $user_notif_worker_timeout_seconds, user_notif_child_timeout_seconds: $user_notif_child_timeout_seconds, cpu_topology_artifact: "cpu-topology.csv", build_log: "build.log", benchmark_log: "benchmark.log", results: "results.jsonl"}' > "${ARTIFACT_DIR}/manifest.json"

docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --privileged --pid=host --userns=host --cpuset-cpus="${CPU_SET}" --security-opt seccomp=unconfined \
    -e "REPOSITORY_DIR=${REPOSITORY_DIR}" -e "WORKLOAD_CPU=${WORKLOAD_CPU}" -e "COLLECTOR_CPU=${COLLECTOR_CPU}" -e "CALLER_UID=${CALLER_UID}" -e "CALLER_GID=${CALLER_GID}" -e "CANONICAL_CACHE=${CANONICAL_CACHE}" -e "CAPTURE_PROFILE=${CAPTURE_PROFILE}" -e "BENCHMARK_SCOPE=${BENCHMARK_SCOPE}" -e "BENCHMARK_WORKLOAD=${BENCHMARK_WORKLOAD}" -e "CHILD_TIMEOUT_SECONDS=${CHILD_TIMEOUT_SECONDS}" -e "USER_NOTIF_WORKER_TIMEOUT_SECONDS=${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" -e "USER_NOTIF_CHILD_TIMEOUT_SECONDS=${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" -e "ITERATIONS=${ITERATIONS}" -e "WARMUPS=${WARMUPS}" -e "BATCHES=${BATCHES}" -e "FORKS=${FORKS}" -e "MAZEWALL_BENCHMARK_SOURCE_REVISION=${SOURCE_REVISION}" -e "MAZEWALL_BENCHMARK_SOURCE_DIRTY=${SOURCE_DIRTY}" -e "MAZEWALL_BENCHMARK_IMAGE_ID=${IMAGE_ID}" \
    -v "${REPOSITORY_DIR}:${REPOSITORY_DIR}:ro" -v "${ARTIFACT_DIR}:/artifacts" -w /tmp "${IMAGE}" bash -lc '
        set -euo pipefail
        mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
        classpath="${REPOSITORY_DIR}/profiler/build/install/tier-e-daemon/lib/*"
        trap "chown \"${CALLER_UID}:${CALLER_GID}\" /artifacts/results.jsonl /artifacts/benchmark.log 2>/dev/null || true" EXIT
        if [[ "${CANONICAL_CACHE}" == "disabled" ]]; then
            java -Dmazewall.tier-e.canonical-cache=disabled -Dmazewall.tier-e.ring-capacity-bytes=8388608 --enable-native-access=ALL-UNNAMED \
                -cp "${classpath}" io.mazewall.profiler.tierE.stress.TierEKernelSmoke \
                /artifacts/libmazewall_stack_agent.so auto "${classpath}" /tmp/tier-e-cache-preflight.mzsd \
                platform 10 "${WORKLOAD_CPU}" full-stream dictionary common-file-workload benchmark 0 1 \
                2>&1 | tee /artifacts/cache-disabled-preflight.log
            grep -Eq "cache_hits=0( |$)" /artifacts/cache-disabled-preflight.log
            grep -Eq "cache_misses=[1-9][0-9]*( |$)" /artifacts/cache-disabled-preflight.log
        fi
        java -Dmazewall.tier-e.canonical-cache="${CANONICAL_CACHE}" -Dmazewall.tier-e.capture-profile="${CAPTURE_PROFILE}" -Dmazewall.tier-e.benchmark-scope="${BENCHMARK_SCOPE}" -Dmazewall.tier-e.benchmark-workload="${BENCHMARK_WORKLOAD}" -Dmazewall.tier-e.child-timeout-seconds="${CHILD_TIMEOUT_SECONDS}" -Dmazewall.tier-e.user-notif-worker-timeout-seconds="${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" -Dmazewall.tier-e.user-notif-child-timeout-seconds="${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" --enable-native-access=ALL-UNNAMED -cp "${classpath}" io.mazewall.profiler.tierE.stress.TierEMatchedBenchmark /artifacts/libmazewall_stack_agent.so "${ITERATIONS}" "${WARMUPS}" "${BATCHES}" "${FORKS}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}" /artifacts/results.jsonl 2>&1 | tee /artifacts/benchmark.log
    '

#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ "${1:-}" == "--help" ]]; then
    cat <<'EOF'
Usage: scripts/run_tier_e_trustworthy_benchmark.sh [artifact-directory]

Runs the pinned, rootful Tier E benchmark matrix. Configure scope, workload,
lifecycle scenario, and dimensions with MAZEWALL_TIER_E_* environment variables.
EOF
    exit 0
fi
IMAGE=${MAZEWALL_TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:perf-20260919-external-profiler}
ARTIFACT_DIR=${1:-"${REPOSITORY_DIR}/build/tier-e-trustworthy-benchmark"}
mkdir -p "${ARTIFACT_DIR}"
ARTIFACT_DIR=$(cd "${ARTIFACT_DIR}" && pwd)
BUNDLE_DIR="${ARTIFACT_DIR}/bundle"
BUILD_CACHE_DIR=${MAZEWALL_TIER_E_BUILD_CACHE_ROOT:-"${REPOSITORY_DIR}/build/tier-e-benchmark-cache"}
mkdir -p "${BUILD_CACHE_DIR}"
RUN_ID=${MAZEWALL_TIER_E_RUN_ID:-"tier-e-$(date -u +%Y%m%dT%H%M%SZ)-$$"}
RUNNER_EVENTS="${ARTIFACT_DIR}/runner-events.jsonl"
RUNNER_TERMINAL_WRITTEN=false
RUNNER_FAILURE_STATE=FAILED

runner_event() {
    local stage=$1
    local state=$2
    local message=${3:-}
    jq -cn \
        --arg run_id "${RUN_ID}" \
        --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        --arg stage "${stage}" \
        --arg state "${state}" \
        --arg message "${message}" \
        '{kind: "runner_stage", run_id: $run_id, timestamp: $timestamp, stage: $stage, state: $state, message: $message}' \
        >> "${RUNNER_EVENTS}"
}

runner_terminal() {
    local state=$1
    local message=${2:-}
    [[ "${RUNNER_TERMINAL_WRITTEN}" == "false" ]] || return 0
    runner_event runner "${state}" "${message}"
    RUNNER_TERMINAL_WRITTEN=true
}

runner_exit() {
    local status=$?
    trap - EXIT
    if [[ "${RUNNER_TERMINAL_WRITTEN}" == "false" ]]; then
        runner_terminal "${RUNNER_FAILURE_STATE}" "runner exited with status ${status}"
    fi
    exit "${status}"
}

runner_cancel() {
    RUNNER_FAILURE_STATE=CANCELLED
    exit 130
}

trap runner_exit EXIT
trap runner_cancel INT TERM
runner_event runner STARTED "artifact_dir=${ARTIFACT_DIR}"

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
SUSTAINED_LOAD=${MAZEWALL_TIER_E_SUSTAINED_LOAD:-below_capacity}
SUSTAINED_RATE_PER_SECOND=${MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND:-0}
USDT_MARKER_ARTIFACT=false
BENCHMARK_WORKLOAD=${MAZEWALL_TIER_E_BENCHMARK_WORKLOAD:-oracle}
LIFECYCLE_SCENARIO=${MAZEWALL_TIER_E_LIFECYCLE_SCENARIO:-normal}
CHILD_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_CHILD_TIMEOUT_SECONDS:-180}
USER_NOTIF_WORKER_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_USER_NOTIF_WORKER_TIMEOUT_SECONDS:-480}
USER_NOTIF_CHILD_TIMEOUT_SECONDS=${MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS:-540}
RUN_LEVEL=${MAZEWALL_TIER_E_RUN_LEVEL:-development}
CALIBRATION=${MAZEWALL_TIER_E_CALIBRATION:-true}
ITERATIONS=${MAZEWALL_TIER_E_ITERATIONS:-100}
case "${RUN_LEVEL}" in
    smoke)
        WARMUPS=${MAZEWALL_TIER_E_WARMUPS:-3}
        BATCHES=${MAZEWALL_TIER_E_BATCHES:-3}
        FORKS=${MAZEWALL_TIER_E_FORKS:-1}
        ;;
    development)
        WARMUPS=${MAZEWALL_TIER_E_WARMUPS:-3}
        BATCHES=${MAZEWALL_TIER_E_BATCHES:-10}
        FORKS=${MAZEWALL_TIER_E_FORKS:-5}
        ;;
    decision)
        WARMUPS=${MAZEWALL_TIER_E_WARMUPS:-3}
        BATCHES=${MAZEWALL_TIER_E_BATCHES:-10}
        FORKS=${MAZEWALL_TIER_E_FORKS:-15}
        ;;
    custom)
        WARMUPS=${MAZEWALL_TIER_E_WARMUPS:-3}
        BATCHES=${MAZEWALL_TIER_E_BATCHES:-10}
        FORKS=${MAZEWALL_TIER_E_FORKS:-5}
        ;;
    *)
        echo "MAZEWALL_TIER_E_RUN_LEVEL must be smoke, development, decision, or custom" >&2
        exit 2
        ;;
esac
DECISION_FORKS=${MAZEWALL_TIER_E_DECISION_FORKS:-15}
REQUIRE_DECISION_FORKS=${MAZEWALL_TIER_E_REQUIRE_DECISION_FORKS:-false}
if [[ "${RUN_LEVEL}" == "decision" ]]; then
    REQUIRE_DECISION_FORKS=true
fi
VERIFY_BUILD=${MAZEWALL_TIER_E_VERIFY_BUILD:-false}
COLLECTOR_RECEIPT_CLOCK=${MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK:-disabled}
PERF_RECORD=${MAZEWALL_TIER_E_PERF_RECORD:-false}
PERF_CALL_GRAPH=${MAZEWALL_TIER_E_PERF_CALL_GRAPH:-dwarf}
PERF_JIT_MAP=${MAZEWALL_TIER_E_PERF_JIT_MAP:-true}
BENCHMARK_JAVA_OPTIONS=${MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS:-}
NATIVE_BUILD_TYPE=${MAZEWALL_TIER_E_NATIVE_BUILD_TYPE:-Release}
ALLOCATION_RECORD=${MAZEWALL_TIER_E_ALLOCATION_RECORD:-false}
IMAGE_ID=$(docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" image inspect --format '{{.Id}}' "${IMAGE}" 2>/dev/null || printf 'unknown')
KERNEL_RELEASE=$(uname -r)
CPU_TOPOLOGY_FILE="${ARTIFACT_DIR}/cpu-topology.csv"
lscpu -p=CPU,CORE,SOCKET > "${CPU_TOPOLOGY_FILE}"
if [[ "${CANONICAL_CACHE}" != "enabled" && "${CANONICAL_CACHE}" != "disabled" ]]; then
    echo "MAZEWALL_TIER_E_CANONICAL_CACHE must be enabled or disabled" >&2
    exit 2
fi
if [[ -n "${CAPTURE_PROFILE}" && "${CAPTURE_PROFILE}" != "jdk25-unix-openat0" && ! "${CAPTURE_PROFILE}" =~ ^capture-bindings=([a-z0-9-]+)(\+[a-z0-9-]+)*$ ]]; then
    echo "MAZEWALL_TIER_E_CAPTURE_PROFILE must be empty, jdk25-unix-openat0, or capture-bindings=name+name" >&2
    exit 2
fi
if [[ "${BENCHMARK_WORKLOAD}" != "application" && "${BENCHMARK_WORKLOAD}" != "oracle" && "${BENCHMARK_WORKLOAD}" != "syscall_dense" && "${BENCHMARK_WORKLOAD}" != "mixed_cpu_io" && "${BENCHMARK_WORKLOAD}" != "bursty_io" && "${BENCHMARK_WORKLOAD}" != "calibration_cpu" && "${BENCHMARK_WORKLOAD}" != "stack_diverse" && "${BENCHMARK_WORKLOAD}" != "class_churn" ]]; then
    echo "MAZEWALL_TIER_E_BENCHMARK_WORKLOAD must be application, oracle, syscall_dense, mixed_cpu_io, bursty_io, calibration_cpu, stack_diverse, or class_churn" >&2
    exit 2
fi
if [[ "${LIFECYCLE_SCENARIO}" != "normal" && "${LIFECYCLE_SCENARIO}" != "detach_while_active" &&
    "${LIFECYCLE_SCENARIO}" != "callback_suppression" && "${LIFECYCLE_SCENARIO}" != "terminal_while_active" &&
    "${LIFECYCLE_SCENARIO}" != "capture_failure" && "${LIFECYCLE_SCENARIO}" != "exec_transition" ]]; then
    echo "MAZEWALL_TIER_E_LIFECYCLE_SCENARIO must be normal, detach_while_active, callback_suppression, terminal_while_active, capture_failure, or exec_transition" >&2
    exit 2
fi
if [[ "${BENCHMARK_SCOPE}" != "full" && "${BENCHMARK_SCOPE}" != "tier-e-comparable" && "${BENCHMARK_SCOPE}" != "tier-e-calibration" && "${BENCHMARK_SCOPE}" != "tier-e-cache-ab" && "${BENCHMARK_SCOPE}" != "tier-e-cache-paired" && "${BENCHMARK_SCOPE}" != "tier-e-publication" && "${BENCHMARK_SCOPE}" != "tier-e-dictionary-publication" && "${BENCHMARK_SCOPE}" != "tier-e-capture-profile" && "${BENCHMARK_SCOPE}" != "tier-e-capture-timing" && "${BENCHMARK_SCOPE}" != "tier-e-ring-decode" && "${BENCHMARK_SCOPE}" != "tier-e-readiness" && "${BENCHMARK_SCOPE}" != "tier-e-poll-backoff" && "${BENCHMARK_SCOPE}" != "tier-e-ring-argument-list" && "${BENCHMARK_SCOPE}" != "tier-e-ring-argument-view" && "${BENCHMARK_SCOPE}" != "tier-e-task-identity" && "${BENCHMARK_SCOPE}" != "tier-e-known-join" && "${BENCHMARK_SCOPE}" != "tier-e-ring-submission-control" && "${BENCHMARK_SCOPE}" != "tier-e-retention-exhaustion" && "${BENCHMARK_SCOPE}" != "tier-e-owned-arguments" && "${BENCHMARK_SCOPE}" != "tier-e-ready-transfer" && "${BENCHMARK_SCOPE}" != "tier-e-collector-append" && "${BENCHMARK_SCOPE}" != "tier-e-unique-edge-observer" && "${BENCHMARK_SCOPE}" != "tier-e-marker-transport" && "${BENCHMARK_SCOPE}" != "tier-e-marker-transport-comparable" && "${BENCHMARK_SCOPE}" != "tier-e-lifecycle" && "${BENCHMARK_SCOPE}" != "tier-e-production-selection" && "${BENCHMARK_SCOPE}" != "tier-e-sustained-collector" ]]; then
    echo "MAZEWALL_TIER_E_BENCHMARK_SCOPE is not a supported Tier E benchmark scope" >&2
    exit 2
fi
if [[ "${SUSTAINED_LOAD}" != "below_capacity" && "${SUSTAINED_LOAD}" != "near_capacity" && "${SUSTAINED_LOAD}" != "intentional_overload" ]]; then
    echo "MAZEWALL_TIER_E_SUSTAINED_LOAD must be below_capacity, near_capacity, or intentional_overload" >&2
    exit 2
fi
if ! [[ "${SUSTAINED_RATE_PER_SECOND}" =~ ^[0-9]+$ ]] || { [[ "${BENCHMARK_SCOPE}" == "tier-e-sustained-collector" ]] && [[ "${SUSTAINED_RATE_PER_SECOND}" == "0" ]]; }; then
    echo "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND must be positive for tier-e-sustained-collector and non-negative otherwise" >&2
    exit 2
fi
if [[ "${CALIBRATION}" != "true" && "${CALIBRATION}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_CALIBRATION must be true or false" >&2
    exit 2
fi
if [[ "$(uname -m)" == "x86_64" ]]; then
    USDT_MARKER_ARTIFACT=true
    if ! docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --entrypoint /bin/sh "${IMAGE}" -c 'printf "#include <sys/sdt.h>\n" | c++ -x c++ -c -o /dev/null -'; then
        echo "${IMAGE} cannot compile a sys/sdt.h include; rebuild it from infra/dev/Containerfile or set MAZEWALL_TIER_E_DOCKER_IMAGE to a USDT-capable image" >&2
        exit 2
    fi
fi
SOURCE_REVISION=$(git -C "${REPOSITORY_DIR}" rev-parse HEAD)
SOURCE_DIRTY=$(git -C "${REPOSITORY_DIR}" status --porcelain --untracked-files=no | wc -l | tr -d ' ')
if [[ "${BENCHMARK_SCOPE}" == "tier-e-capture-profile" && -n "${CAPTURE_PROFILE}" ]]; then
    echo "tier-e-capture-profile selects full and jdk25-unix-openat0 internally; leave MAZEWALL_TIER_E_CAPTURE_PROFILE empty" >&2
    exit 2
fi
if ! [[ "${ITERATIONS}" =~ ^[1-9][0-9]*$ && "${WARMUPS}" =~ ^[0-9]+$ && "${BATCHES}" =~ ^[1-9][0-9]*$ && "${FORKS}" =~ ^[1-9][0-9]*$ && "${DECISION_FORKS}" =~ ^[1-9][0-9]*$ && "${CHILD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ && "${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ && "${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    echo "benchmark dimensions and deadlines must be non-negative/positive integers" >&2
    exit 2
fi
if [[ "${REQUIRE_DECISION_FORKS}" != "true" && "${REQUIRE_DECISION_FORKS}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_REQUIRE_DECISION_FORKS must be true or false" >&2
    exit 2
fi
if [[ "${VERIFY_BUILD}" != "true" && "${VERIFY_BUILD}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_VERIFY_BUILD must be true or false" >&2
    exit 2
fi
if [[ "${PERF_RECORD}" != "true" && "${PERF_RECORD}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_PERF_RECORD must be true or false" >&2
    exit 2
fi
if [[ "${ALLOCATION_RECORD}" != "true" && "${ALLOCATION_RECORD}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_ALLOCATION_RECORD must be true or false" >&2
    exit 2
fi
if [[ "${PERF_RECORD}" == "true" && "${ALLOCATION_RECORD}" == "true" ]]; then
    echo "CPU perf and allocation perf recording are mutually exclusive" >&2
    exit 2
fi
if [[ "${PERF_JIT_MAP}" != "true" && "${PERF_JIT_MAP}" != "false" ]]; then
    echo "MAZEWALL_TIER_E_PERF_JIT_MAP must be true or false" >&2
    exit 2
fi
if [[ "${PERF_CALL_GRAPH}" != "dwarf" && "${PERF_CALL_GRAPH}" != "fp" && "${PERF_CALL_GRAPH}" != "lbr" ]]; then
    echo "MAZEWALL_TIER_E_PERF_CALL_GRAPH must be dwarf, fp, or lbr" >&2
    exit 2
fi
if [[ "${REQUIRE_DECISION_FORKS}" == "true" ]] && (( FORKS < DECISION_FORKS )); then
    echo "decision benchmark requires at least ${DECISION_FORKS} forks; got ${FORKS}" >&2
    exit 2
fi
if (( USER_NOTIF_CHILD_TIMEOUT_SECONDS <= USER_NOTIF_WORKER_TIMEOUT_SECONDS )); then
    echo "MAZEWALL_TIER_E_USER_NOTIF_CHILD_TIMEOUT_SECONDS must exceed the worker timeout" >&2
    exit 2
fi

runner_event bundle_build STARTED
docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --privileged --pid=host --userns=host --cpuset-cpus="${CPU_SET}" --security-opt seccomp=unconfined \
    --user "${CALLER_UID}:${CALLER_GID}" \
    -e "WORKLOAD_CPU=${WORKLOAD_CPU}" -e "COLLECTOR_CPU=${COLLECTOR_CPU}" \
    -e "CAPTURE_PROFILE=${CAPTURE_PROFILE}" -e "VERIFY_BUILD=${VERIFY_BUILD}" -e "USDT_MARKER_ARTIFACT=${USDT_MARKER_ARTIFACT}" \
    -e "MAZEWALL_TIER_E_NATIVE_BUILD_TYPE=${NATIVE_BUILD_TYPE}" \
    -e "MAZEWALL_TIER_E_REPOSITORY_DIR=${REPOSITORY_DIR}" -e MAZEWALL_TIER_E_BUILD_CACHE_ROOT=/benchmark-cache \
    -v "${REPOSITORY_DIR}:${REPOSITORY_DIR}" -v "${ARTIFACT_DIR}:/artifacts" -v "${BUILD_CACHE_DIR}:/benchmark-cache" -w "${REPOSITORY_DIR}" "${IMAGE}" bash -lc '
        set -euo pipefail
        {
            bash scripts/build_tier_e_benchmark_bundle.sh /artifacts/bundle "${USDT_MARKER_ARTIFACT}"
            if [[ "${VERIFY_BUILD}" == "true" ]]; then
                ./gradlew --no-daemon :profiler:test --tests io.mazewall.profiler.tierE.stress.TierECommonFileWorkloadTest
            fi
        } 2>&1 | tee /artifacts/build.log
    '
runner_event bundle_build COMPLETED

runner_event bundle_verify STARTED
bash "${REPOSITORY_DIR}/scripts/build_tier_e_benchmark_bundle.sh" --verify "${BUNDLE_DIR}"
runner_event bundle_verify COMPLETED
SOURCE_FINGERPRINT=$(jq -r '.input_fingerprint // empty' "${BUNDLE_DIR}/manifest.json")
[[ "${SOURCE_FINGERPRINT}" =~ ^[a-f0-9]{64}$ ]] || { echo "bundle has no valid input fingerprint" >&2; exit 1; }
AGENT_SHA256=$(sha256sum "${BUNDLE_DIR}/libmazewall_stack_agent.so" | awk '{print $1}')
BUNDLE_MANIFEST_SHA256=$(sha256sum "${BUNDLE_DIR}/manifest.json" | awk '{print $1}')
jq -n \
    --arg run_id "${RUN_ID}" \
    --arg source_revision "${SOURCE_REVISION}" \
    --arg source_dirty_count "${SOURCE_DIRTY}" \
    --arg source_fingerprint "${SOURCE_FINGERPRINT}" \
    --arg image "${IMAGE}" \
    --arg image_id "${IMAGE_ID}" \
    --arg agent_sha256 "${AGENT_SHA256}" \
    --arg bundle_manifest_sha256 "${BUNDLE_MANIFEST_SHA256}" \
    --arg kernel_release "${KERNEL_RELEASE}" \
    --arg cpu_set "${CPU_SET}" \
    --arg workload_cpu "${WORKLOAD_CPU}" \
    --arg collector_cpu "${COLLECTOR_CPU}" \
    --arg scope "${BENCHMARK_SCOPE}" \
    --arg sustained_load "${SUSTAINED_LOAD}" \
    --argjson sustained_rate_per_second "${SUSTAINED_RATE_PER_SECOND}" \
    --arg workload "${BENCHMARK_WORKLOAD}" \
    --arg lifecycle_scenario "${LIFECYCLE_SCENARIO}" \
    --argjson perf_record "${PERF_RECORD}" \
    --argjson perf_jit_map "${PERF_JIT_MAP}" \
    --argjson allocation_record "${ALLOCATION_RECORD}" \
    --arg perf_call_graph "${PERF_CALL_GRAPH}" \
    --arg benchmark_java_options "${BENCHMARK_JAVA_OPTIONS}" \
    --arg native_build_type "${NATIVE_BUILD_TYPE}" \
    --argjson usdt_marker_artifact "${USDT_MARKER_ARTIFACT}" \
    --argjson verify_build "${VERIFY_BUILD}" \
    --argjson iterations "${ITERATIONS}" \
    --argjson warmups "${WARMUPS}" \
    --argjson batches "${BATCHES}" \
    --argjson forks "${FORKS}" \
    --argjson decision_forks "${DECISION_FORKS}" \
    --argjson child_timeout_seconds "${CHILD_TIMEOUT_SECONDS}" \
    --argjson user_notif_worker_timeout_seconds "${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" \
    --argjson user_notif_child_timeout_seconds "${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" \
    '{schema_version: 2, run_id: $run_id, source_revision: $source_revision, source_dirty_count: $source_dirty_count, source_fingerprint: $source_fingerprint, image: $image, image_id: $image_id, agent_sha256: $agent_sha256, bundle_manifest_sha256: $bundle_manifest_sha256, kernel_release: $kernel_release, cpu_set: $cpu_set, workload_cpu: $workload_cpu, collector_cpu: $collector_cpu, scope: $scope, sustained_load: $sustained_load, sustained_rate_per_second: $sustained_rate_per_second, workload: $workload, lifecycle_scenario: $lifecycle_scenario, perf_record: $perf_record, perf_jit_map: $perf_jit_map, allocation_record: $allocation_record, perf_call_graph: $perf_call_graph, benchmark_java_options: $benchmark_java_options, native_build_type: $native_build_type, usdt_marker_artifact: $usdt_marker_artifact, verify_build: $verify_build, iterations: $iterations, warmups: $warmups, batches: $batches, forks: $forks, decision_forks: $decision_forks, child_timeout_seconds: $child_timeout_seconds, user_notif_worker_timeout_seconds: $user_notif_worker_timeout_seconds, user_notif_child_timeout_seconds: $user_notif_child_timeout_seconds, bundle: "bundle", cpu_topology_artifact: "cpu-topology.csv", build_log: "build.log", benchmark_log: "benchmark.log", results: "results.jsonl", runner_events: "runner-events.jsonl", perf_data: (if $perf_record then "perf.data" else null end), perf_report: (if $perf_record then "perf-report.txt" else null end), perf_pid_tid_samples: (if $perf_record then "perf-pid-tid-samples.txt" else null end), perf_process_attribution: (if $perf_record then "perf-process-attribution.json" else null end), perf_jit_maps: (if ($perf_record and $perf_jit_map) then "perf-maps" else null end), allocation_data: (if $allocation_record then "allocation.data" else null end), allocation_events: (if $allocation_record then "allocation-events.txt" else null end), allocation_by_pid: (if $allocation_record then "allocation-by-pid.json" else null end), allocation_summary: (if $allocation_record then "allocation-summary.json" else null end)}' > "${ARTIFACT_DIR}/manifest.json"
manifest_tmp="${ARTIFACT_DIR}/manifest.json.tmp"
jq --arg run_level "${RUN_LEVEL}" --argjson calibration "${CALIBRATION}" \
    '. + {run_level: $run_level, calibration: $calibration}' "${ARTIFACT_DIR}/manifest.json" > "${manifest_tmp}"
mv "${manifest_tmp}" "${ARTIFACT_DIR}/manifest.json"

runner_event benchmark STARTED
docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --privileged --pid=host --userns=host --cpuset-cpus="${CPU_SET}" --security-opt seccomp=unconfined \
    -e REPOSITORY_DIR=/bundle -e "WORKLOAD_CPU=${WORKLOAD_CPU}" -e "COLLECTOR_CPU=${COLLECTOR_CPU}" -e "CALLER_UID=${CALLER_UID}" -e "CALLER_GID=${CALLER_GID}" -e "CANONICAL_CACHE=${CANONICAL_CACHE}" -e "CAPTURE_PROFILE=${CAPTURE_PROFILE}" -e "COLLECTOR_RECEIPT_CLOCK=${COLLECTOR_RECEIPT_CLOCK}" -e "BENCHMARK_SCOPE=" -e "SUSTAINED_LOAD=" -e "SUSTAINED_RATE_PER_SECOND=" -e "BENCHMARK_WORKLOAD=" -e "LIFECYCLE_SCENARIO=${LIFECYCLE_SCENARIO}" -e "CHILD_TIMEOUT_SECONDS=${CHILD_TIMEOUT_SECONDS}" -e "USER_NOTIF_WORKER_TIMEOUT_SECONDS=${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" -e "USER_NOTIF_CHILD_TIMEOUT_SECONDS=${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" -e "ITERATIONS=${ITERATIONS}" -e "WARMUPS=${WARMUPS}" -e "BATCHES=${BATCHES}" -e "FORKS=${FORKS}" -e "RUN_LEVEL=${RUN_LEVEL}" -e "CALIBRATION=${CALIBRATION}" -e "PERF_RECORD=${PERF_RECORD}" -e "PERF_JIT_MAP=${PERF_JIT_MAP}" -e "ALLOCATION_RECORD=${ALLOCATION_RECORD}" -e "PERF_CALL_GRAPH=${PERF_CALL_GRAPH}" -e "BENCHMARK_JAVA_OPTIONS=${BENCHMARK_JAVA_OPTIONS}" -e "MAZEWALL_TIER_E_NATIVE_BUILD_TYPE=${NATIVE_BUILD_TYPE}" -e "MAZEWALL_BENCHMARK_SOURCE_REVISION=${SOURCE_REVISION}" -e "MAZEWALL_BENCHMARK_SOURCE_DIRTY=${SOURCE_DIRTY}" -e "MAZEWALL_BENCHMARK_IMAGE_ID=${IMAGE_ID}" \
    -v "${BUNDLE_DIR}:/bundle:ro" -v "${ARTIFACT_DIR}:/artifacts" -w /tmp "${IMAGE}" bash -lc '
        set -euo pipefail
        mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
        classpath="/bundle/runtime/*"
        allocation_probe_active=false
        cleanup_benchmark() {
            local status=$?
            if [[ "${allocation_probe_active}" == "true" ]]; then
                perf probe --del probe_libc:malloc >/dev/null 2>&1 || true
            fi
            chown "${CALLER_UID}:${CALLER_GID}" /artifacts/results.jsonl /artifacts/calibration-results.jsonl /artifacts/benchmark.log /artifacts/allocation.data 2>/dev/null || true
            return "${status}"
        }
        trap cleanup_benchmark EXIT
        if [[ "${CANONICAL_CACHE}" == "disabled" ]]; then
            java -Dmazewall.tier-e.canonical-cache=disabled -Dmazewall.tier-e.ring-capacity-bytes=8388608 --enable-native-access=ALL-UNNAMED \
                -cp "${classpath}" io.mazewall.profiler.tierE.stress.TierEKernelSmoke \
                /bundle/libmazewall_stack_agent.so auto "${classpath}" /tmp/tier-e-cache-preflight.mzsd \
                platform 10 "${WORKLOAD_CPU}" full-stream dictionary common-file-workload benchmark 0 1 \
                2>&1 | tee /artifacts/cache-disabled-preflight.log
            grep -Eq "cache_hits=0( |$)" /artifacts/cache-disabled-preflight.log
            grep -Eq "cache_misses=[1-9][0-9]*( |$)" /artifacts/cache-disabled-preflight.log
        fi
        read -r -a benchmark_java_options <<< "${BENCHMARK_JAVA_OPTIONS:-}"
        if [[ "${PERF_RECORD}" == "true" && "${PERF_JIT_MAP}" == "true" ]]; then
            benchmark_java_options+=("-XX:+UnlockDiagnosticVMOptions" "-XX:+DumpPerfMapAtExit")
        fi
        # Inherit diagnostic options through every nested JVM, including native workloads.
        # This environment exists only in the disposable benchmark container.
        export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} ${benchmark_java_options[*]}"
        benchmark_java=(java "${benchmark_java_options[@]}" -Dmazewall.tier-e.canonical-cache="${CANONICAL_CACHE}" -Dmazewall.tier-e.capture-profile="${CAPTURE_PROFILE}" -Dmazewall.tier-e.benchmark-scope="" -Dmazewall.tier-e.sustained-load="" -Dmazewall.tier-e.sustained-rate-per-second="" -Dmazewall.tier-e.benchmark-workload="" -Dmazewall.tier-e.lifecycle-scenario="${LIFECYCLE_SCENARIO}" -Dmazewall.tier-e.collector-receipt-clock="${COLLECTOR_RECEIPT_CLOCK}" -Dmazewall.tier-e.calibration="${CALIBRATION}" -Dmazewall.tier-e.run-level="${RUN_LEVEL}" -Dmazewall.tier-e.dump-perf-map="$([[ "${PERF_RECORD}" == "true" && "${PERF_JIT_MAP}" == "true" ]] && printf true || printf false)" -Dmazewall.tier-e.child-timeout-seconds="${CHILD_TIMEOUT_SECONDS}" -Dmazewall.tier-e.user-notif-worker-timeout-seconds="${USER_NOTIF_WORKER_TIMEOUT_SECONDS}" -Dmazewall.tier-e.user-notif-child-timeout-seconds="${USER_NOTIF_CHILD_TIMEOUT_SECONDS}" --enable-native-access=ALL-UNNAMED -cp "${classpath}" io.mazewall.profiler.tierE.stress.TierEMatchedBenchmark /bundle/libmazewall_stack_agent.so "${ITERATIONS}" "${WARMUPS}" "${BATCHES}" "${FORKS}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}" /artifacts/results.jsonl)
        if [[ "${PERF_RECORD}" == "true" ]]; then
            # Record from launch; offline monotonic windows select measured work per fork.
            perf record --output /artifacts/perf.data --clockid mono --event cycles:u --call-graph "${PERF_CALL_GRAPH}" -- "${benchmark_java[@]}" 2>&1 | tee /artifacts/benchmark.log
            if [[ "${PERF_JIT_MAP}" == "true" ]]; then
                mkdir -p /artifacts/perf-maps
                cp /tmp/perf-*.map /artifacts/perf-maps/
            fi
        elif [[ "${ALLOCATION_RECORD}" == "true" ]]; then
            perf probe -x /usr/lib/x86_64-linux-gnu/libc.so.6 "malloc size=%di:s64"
            allocation_probe_active=true
            perf record --output /artifacts/allocation.data --event probe_libc:malloc -- "${benchmark_java[@]}" 2>&1 | tee /artifacts/benchmark.log
        else
            "${benchmark_java[@]}" 2>&1 | tee /artifacts/benchmark.log
        fi
    '
runner_event benchmark COMPLETED

selected_iterations="$(jq -r 'select(.kind == "metadata") | .iterations' "${ARTIFACT_DIR}/results.jsonl")"
calibration_target="$(jq -r 'select(.kind == "metadata") | .calibration_target' "${ARTIFACT_DIR}/results.jsonl")"
manifest_tmp="${ARTIFACT_DIR}/manifest.json.tmp"
jq --argjson selected_iterations "${selected_iterations}" --arg calibration_target "${calibration_target}" \
    '. + {selected_iterations: $selected_iterations, calibration_target: $calibration_target}' \
    "${ARTIFACT_DIR}/manifest.json" > "${manifest_tmp}"
mv "${manifest_tmp}" "${ARTIFACT_DIR}/manifest.json"

if [[ "${PERF_RECORD}" == "true" && "${PERF_JIT_MAP}" == "true" ]]; then
    jq -r '[select(.kind == "fork-output") | .target_pid,
        (.output | scan("(?:collector|workload)_pid=([0-9]+)") | .[0] | tonumber),
        (.output | scan("tier-e-perf-window: pid=([0-9]+)") | .[0] | tonumber)]
        | .[] | select(. != null)' "${ARTIFACT_DIR}/results.jsonl" |
        sort -nu > "${ARTIFACT_DIR}/perf-map-required-pids.txt"
    while read -r required_pid; do
        if [[ ! -s "${ARTIFACT_DIR}/perf-maps/perf-${required_pid}.map" ]]; then
            echo "Missing JIT perf map for benchmark PID ${required_pid}" >&2
            exit 1
        fi
    done < "${ARTIFACT_DIR}/perf-map-required-pids.txt"
fi

if [[ "${PERF_RECORD}" == "true" ]]; then
    runner_event perf_analysis STARTED
    docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm \
        -v "${ARTIFACT_DIR}:/artifacts" "${IMAGE}" bash -lc '
            set -euo pipefail
            if [[ -d /artifacts/perf-maps ]]; then
                cp /artifacts/perf-maps/perf-*.map /tmp/ 2>/dev/null || true
            fi
            perf buildid-cache -a /artifacts/bundle/libmazewall_stack_agent.so
            perf report --stdio --no-children -i /artifacts/perf.data > /artifacts/perf-report.txt
            perf script -F comm,pid,tid,event,ip,sym,dso -i /artifacts/perf.data > /artifacts/perf-pid-tid-samples.txt
            perf script --ns -F comm,pid,tid,time,event,ip,sym,dso -i /artifacts/perf.data > /artifacts/perf-timed-samples.txt
            chown "'"${CALLER_UID}:${CALLER_GID}"'" /artifacts/perf-report.txt /artifacts/perf-pid-tid-samples.txt
        '
    jq -s '[.[] | select(.kind == "fork-output") | (.output | capture("collector_pid=(?<collector_pid>[0-9]+) workload_pid=(?<workload_pid>[0-9]+)")) as $p | {variant, fork, collector_pid: ($p.collector_pid | tonumber), workload_pid: ($p.workload_pid | tonumber)}]' "${ARTIFACT_DIR}/results.jsonl" > "${ARTIFACT_DIR}/perf-processes.json"
    awk '/^[^[:space:]]+[[:space:]]+[0-9]+\/[0-9]+[[:space:]]/ { split($2, ids, "/"); samples[ids[1]]++ } END { print "["; separator = ""; for (pid in samples) { printf "%s{\"pid\":%s,\"samples\":%s}", separator, pid, samples[pid]; separator = "," } print "]" }' "${ARTIFACT_DIR}/perf-pid-tid-samples.txt" | jq -S . > "${ARTIFACT_DIR}/perf-pid-sample-counts.json"
    jq -n --slurpfile processes "${ARTIFACT_DIR}/perf-processes.json" --slurpfile samples "${ARTIFACT_DIR}/perf-pid-sample-counts.json" '
        {
          schema_version: 1,
          measurement: "external_user_cycles_by_retained_process_identity",
          entries: [
            $processes[0][] as $process |
            [
              {variant: $process.variant, fork: $process.fork, role: "collector", pid: $process.collector_pid},
              {variant: $process.variant, fork: $process.fork, role: "workload", pid: $process.workload_pid}
            ][] as $entry |
            $entry + {samples: (([$samples[0][] | select(.pid == $entry.pid)][0].samples) // 0)}
          ]
        }
    ' > "${ARTIFACT_DIR}/perf-process-attribution.json"
    runner_event perf_analysis COMPLETED
fi

if [[ "${ALLOCATION_RECORD}" == "true" ]]; then
    runner_event allocation_analysis STARTED
    docker --host "${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" run --rm --privileged --security-opt seccomp=unconfined \
        -v "${ARTIFACT_DIR}:/artifacts" "${IMAGE}" bash -lc '
            set -euo pipefail
            cleanup_allocation_probe() { perf probe --del probe_libc:malloc >/dev/null 2>&1 || true; }
            trap cleanup_allocation_probe EXIT
            perf probe -x /usr/lib/x86_64-linux-gnu/libc.so.6 "malloc size=%di:s64"
            perf script -f -i /artifacts/allocation.data > /artifacts/allocation-events.txt
            chown "'"${CALLER_UID}:${CALLER_GID}"'" /artifacts/allocation-events.txt
        '
    sed -nE 's/^[[:space:]]*[^[:space:]]+[[:space:]]+([0-9]+).*size=([0-9]+).*/\1 \2/p' "${ARTIFACT_DIR}/allocation-events.txt" |
        awk 'BEGIN { print "["; first = 1 } { count[$1]++; bytes[$1] += $2 } END { for (pid in count) { if (!first) printf ",\n"; printf "{\"pid\":%s,\"allocation_count\":%s,\"requested_bytes\":%.0f}", pid, count[pid], bytes[pid]; first = 0 } print "\n]" }' |
        jq -S . > "${ARTIFACT_DIR}/allocation-by-pid.json"
    jq -n --slurpfile allocations "${ARTIFACT_DIR}/allocation-by-pid.json" --slurpfile results "${ARTIFACT_DIR}/results.jsonl" '
        {
          schema_version: 1,
          measurement: "external_libc_malloc_requested_bytes",
          coverage: "lower_bound_malloc_only",
          entries: [
            $allocations[0][] | . as $allocation |
            ([$results[] | select(.kind == "fork-output" and .target_pid == $allocation.pid)][0] // {}) as $fork |
            $allocation + {
              variant: ($fork.variant // null),
              fork: ($fork.fork // null),
              workload_correctness: ($fork.workload_correctness // null),
              whole_session_evidence: ($fork.whole_session_evidence // null)
            }
          ]
        }
    ' > "${ARTIFACT_DIR}/allocation-summary.json"
    runner_event allocation_analysis COMPLETED
fi

jq -e -s '
    ([.[] | select(.kind == "terminal")] | length == 1) and
    ([.[] | select(.kind == "terminal" and .success == true)] | length == 1) and
    ([.[] | select(.kind == "summary")] | length == 1) and
    ([.[] | select(.kind == "fork-output")] | length > 0) and
    (([.[] | select(.kind == "sample")] | length) == ([.[] | select(.kind == "summary")][0].sample_count))
' "${ARTIFACT_DIR}/results.jsonl" > /dev/null
runner_terminal PASSED "benchmark artifact validated"

#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
ARTIFACT_DIR=${TIER_E_SPLIT_ARTIFACT_DIR:-"${ROOT}/build/tier-e-split-privilege"}
RUN_ID="${TIER_E_SPLIT_RUN_ID:-tier-e-split-$$}"
SHARED=$(mktemp -d)
TARGET="${RUN_ID}-target"
COLLECTOR="${RUN_ID}-collector"
CALLER_UID=$(id -u)
CALLER_GID=$(id -g)
NATIVE_BUILD_DIR=${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}
REUSE_BUILD=${TIER_E_SPLIT_REUSE_BUILD:-0}
NEGATIVE_ARTIFACT_CASE=${TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE:-}
NEGATIVE_IDENTITY_CASE=${TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE:-}
DELAYED_ARTIFACT=${TIER_E_SPLIT_DELAYED_ARTIFACT:-0}
SIMULATED_PID_REUSE=${TIER_E_SPLIT_SIMULATED_PID_REUSE:-0}
REAL_PID_REUSE=${TIER_E_SPLIT_REAL_PID_REUSE:-0}
SPLIT_COVERAGE=${TIER_E_SPLIT_COVERAGE:-0}
SPLIT_BENCHMARK=${TIER_E_SPLIT_BENCHMARK:-0}
SPLIT_COVERAGE_OPERATIONS=${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}
ALLOW_INCOMPLETE=${TIER_E_SPLIT_ALLOW_INCOMPLETE:-0}
SPLIT_EMISSION_MODE=${TIER_E_SPLIT_EMISSION_MODE:-full-stream}
SPLIT_STACK_TRANSPORT=${TIER_E_SPLIT_STACK_TRANSPORT:-dictionary}
SPLIT_SOCKET_ACCESS=${TIER_E_SPLIT_SOCKET_ACCESS:-group}
BENCH_WARMUPS=${TIER_E_SPLIT_BENCH_WARMUPS:-3}
BENCH_BATCHES=${TIER_E_SPLIT_BENCH_BATCHES:-10}
BENCH_ITERATIONS=${TIER_E_SPLIT_BENCH_ITERATIONS:-100}
SPLIT_MARKER_VERSION=${TIER_E_SPLIT_MARKER_VERSION:-v1}
SPLIT_MARKER_BACKEND=${TIER_E_SPLIT_MARKER_BACKEND:-uprobe}
SPLIT_RECOVERY_MODE=${TIER_E_SPLIT_RECOVERY_MODE:-disabled}
COVERAGE_BASELINE=${TIER_E_SPLIT_COVERAGE_BASELINE:-0}

cleanup() {
    mkdir -p "${ARTIFACT_DIR}"
    if [[ "${REAL_PID_REUSE}" == 1 ]]; then
        "${ENGINE}" unpause "${COLLECTOR}" >/dev/null 2>&1 || true
        [[ -z "${probe_pid:-}" ]] || wait "${probe_pid}" || true
    fi
    "${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1 || true
    "${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1 || true
    "${ENGINE}" rm -f "${TARGET}" "${COLLECTOR}" >/dev/null 2>&1 || true
    rm -rf "${SHARED}"
}
trap cleanup EXIT

[[ "${ENGINE}" == docker ]] || { echo "rootless runtimes are INCOMPLETE evidence" >&2; exit 2; }
[[ "${SPLIT_EMISSION_MODE}" == full-stream || "${SPLIT_EMISSION_MODE}" == unique-stack-syscall ]] || {
    echo "unknown TIER_E_SPLIT_EMISSION_MODE: ${SPLIT_EMISSION_MODE}" >&2; exit 2;
}
[[ "${SPLIT_STACK_TRANSPORT}" == dictionary || "${SPLIT_STACK_TRANSPORT}" == inline-per-invocation ]] || {
    echo "unknown TIER_E_SPLIT_STACK_TRANSPORT: ${SPLIT_STACK_TRANSPORT}" >&2; exit 2;
}
[[ "${SPLIT_MARKER_VERSION}" == v1 || "${SPLIT_MARKER_VERSION}" == v2 ]] || {
    echo "unknown TIER_E_SPLIT_MARKER_VERSION: ${SPLIT_MARKER_VERSION}" >&2; exit 2;
}
[[ "${SPLIT_MARKER_BACKEND}" == uprobe || "${SPLIT_MARKER_BACKEND}" == usdt ]] || {
    echo "unknown TIER_E_SPLIT_MARKER_BACKEND: ${SPLIT_MARKER_BACKEND}" >&2; exit 2;
}
[[ "${SPLIT_RECOVERY_MODE}" == disabled || "${SPLIT_RECOVERY_MODE}" == shadow || "${SPLIT_RECOVERY_MODE}" == recover ]] || {
    echo "unknown TIER_E_SPLIT_RECOVERY_MODE: ${SPLIT_RECOVERY_MODE}" >&2; exit 2;
}
[[ "${SPLIT_RECOVERY_MODE}" == disabled || "${SPLIT_MARKER_VERSION}" == v2 ]] || {
    echo "coverage recovery requires marker v2" >&2; exit 2;
}
[[ "${COVERAGE_BASELINE}" == 0 || "${COVERAGE_BASELINE}" == 1 ]] || {
    echo "unknown TIER_E_SPLIT_COVERAGE_BASELINE: ${COVERAGE_BASELINE}" >&2; exit 2;
}
if [[ "${COVERAGE_BASELINE}" == 1 ]]; then
    [[ "${SPLIT_COVERAGE}" == 1 && "${SPLIT_BENCHMARK}" == 0 && "${REAL_PID_REUSE}" == 0 &&
       "${SIMULATED_PID_REUSE}" == 0 ]] || {
        echo 'coverage baseline requires the coverage fixture without reuse cases' >&2; exit 2;
    }
fi
if [[ "${REAL_PID_REUSE}" == 1 ]]; then
    [[ "${SPLIT_MARKER_VERSION}" == v2 && "${SPLIT_RECOVERY_MODE}" == recover &&
       "${TIER_E_SPLIT_COLLECTOR_BENCHMARK_MODE:-0}" == 1 && "${SPLIT_BENCHMARK}" == 0 &&
       "${SPLIT_COVERAGE}" == 0 && "${SIMULATED_PID_REUSE}" == 0 ]] || {
        echo 'real PID reuse requires the v2 hybrid registered fixture' >&2; exit 2;
    }
fi
[[ "${SPLIT_SOCKET_ACCESS}" == group || "${SPLIT_SOCKET_ACCESS}" == owner ]] || {
    echo "unknown TIER_E_SPLIT_SOCKET_ACCESS: ${SPLIT_SOCKET_ACCESS}" >&2; exit 2;
}
export DOCKER_HOST
mkdir -p "${ARTIFACT_DIR}" "${SHARED}/definitions" "${SHARED}/evidence"
# Keep the mount root traversable so the unrelated-user probe reaches connect(2).
# Definitions and operator evidence remain inaccessible outside the target group.
chmod 0755 "${SHARED}"
chmod 0770 "${SHARED}/definitions" "${SHARED}/evidence"

registered_artifact_file=orders.mzse
[[ "${DELAYED_ARTIFACT}" == 1 ]] && registered_artifact_file=delayed.mzse
case "${NEGATIVE_ARTIFACT_CASE}" in
    "") ;;
    missing) registered_artifact_file=missing.mzse ;;
    malformed)
        registered_artifact_file=malformed.mzse
        printf 'not-an-mzse-envelope' > "${SHARED}/definitions/${registered_artifact_file}"
        ;;
    unclosed|session-mismatch)
        registered_artifact_file="${NEGATIVE_ARTIFACT_CASE}.mzse"
        node -e '
            const fs=require("fs"),[scenario,path]=process.argv.slice(1);
            const payload=Buffer.from([77,90,83,68,2,0]);
            const bytes=Buffer.alloc(40);
            bytes.write("MZSE",0,"ascii"); bytes.writeUInt16LE(2,4);
            bytes.writeUInt16LE(scenario==="unclosed"?0:1,6);
            bytes.writeBigUInt64LE(scenario==="unclosed"?0x4d5an:0x4d5bn,8);
            bytes.writeBigUInt64LE(1n,16);
            bytes.writeBigUInt64LE(0n,24);
            bytes.writeBigUInt64LE(BigInt(payload.length),32);
            fs.writeFileSync(path,Buffer.concat([bytes,payload]));
        ' "${NEGATIVE_ARTIFACT_CASE}" "${SHARED}/definitions/${registered_artifact_file}"
        ;;
    symlink)
        registered_artifact_file=symlink.mzse
        ln -s orders.mzse "${SHARED}/definitions/${registered_artifact_file}"
        ;;
    *) echo "unknown TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE: ${NEGATIVE_ARTIFACT_CASE}" >&2; exit 2 ;;
esac

if [[ "${REUSE_BUILD}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
if [[ ! -f "${NATIVE_BUILD_DIR}/CMakeCache.txt" ]]; then
    cmake -S "${ROOT}/profiler-native" -B "${NATIVE_BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release >/dev/null
fi
if [[ "${REUSE_BUILD}" != 1 ]]; then
    cmake --build "${NATIVE_BUILD_DIR}" -j2 >/dev/null
else
    test -f "${NATIVE_BUILD_DIR}/agent-test-classes/AgentCoverageWorkload.class"
    test -f "${NATIVE_BUILD_DIR}/libmazewall_stack_agent.so"
    test -x "${ROOT}/profiler/build/install/tier-e-daemon/bin/tier-e-daemon"
fi
marker_symbol=mazewall_stack_marker
[[ "${SPLIT_MARKER_VERSION}" == v2 ]] && marker_symbol=mazewall_stack_marker_v2
MARKER_OFFSET_RAW=$(nm -D --defined-only "${NATIVE_BUILD_DIR}/libmazewall_stack_agent.so" | awk -v symbol="${marker_symbol}" '$3 == symbol { print $1; exit }')
[[ -n "${MARKER_OFFSET_RAW}" ]] || { echo "missing ${marker_symbol} symbol" >&2; exit 1; }
MARKER_OFFSET=$(printf '0x%x' "$((16#${MARKER_OFFSET_RAW}))")
if [[ "${REUSE_BUILD}" != 1 ]]; then
    "${ENGINE}" build -t "${IMAGE}" -f "${ROOT}/infra/dev/Containerfile" "${ROOT}" >/dev/null
else
    "${ENGINE}" image inspect "${IMAGE}" >/dev/null
fi

cp "${NATIVE_BUILD_DIR}/libmazewall_stack_agent.so" "${SHARED}/libmazewall_stack_agent.so"
cp -R "${NATIVE_BUILD_DIR}/agent-test-classes" "${SHARED}/agent-test-classes"
registration_version=1
[[ "${SPLIT_MARKER_VERSION}" == v2 ]] && registration_version=2
cat > "${SHARED}/target.properties" <<EOF
version=${registration_version}
registration_id=split-fixture
pid=1
session_tag=4d5a
process_incarnation=1
marker_path=/shared/libmazewall_stack_agent.so
marker_offset=${MARKER_OFFSET}
artifact_file=${registered_artifact_file}
EOF
[[ "${SPLIT_MARKER_VERSION}" == v2 ]] && printf 'marker_proof_file=orders.marker-proof\n' >> "${SHARED}/target.properties"

# The collector's PID namespace is joined to the target. Linux reports the target JVM as PID 1.
# The fixture rewrites process_incarnation after start with its in-namespace /proc starttime.
agent_options="session=4d5a,session-bound-definitions,definitions=/shared/definitions/orders.mzse"
agent_marker_version=${TIER_E_SPLIT_AGENT_MARKER_VERSION:-${SPLIT_MARKER_VERSION}}
[[ "${agent_marker_version}" == v1 || "${agent_marker_version}" == v2 ]] || exit 2
[[ "${agent_marker_version}" == v2 ]] && agent_options+=",marker-version=v2"
if [[ "${SPLIT_MARKER_VERSION}" == v2 && "${TIER_E_SPLIT_DISABLE_MARKER_PROOF:-0}" != 1 ]]; then
    agent_options+=",marker-version-proof=/shared/definitions/orders.marker-proof"
fi
[[ "${SPLIT_STACK_TRANSPORT}" == inline-per-invocation ]] && agent_options+=",stack-transport=inline-per-invocation"
# Instrumented forks export native phase counters (including publication CPU);
# uninstrumented forks keep workload timing free of phase-clock overhead.
if [[ "${TIER_E_SPLIT_BENCHMARK_PHASES:-0}" == 1 ]]; then
    agent_options+=",benchmark-phases"
fi
[[ "${TIER_E_SPLIT_LIVE_DEFINITIONS:-0}" == 1 ]] && agent_options+=",live-definitions"
target_agent_flag="-agentpath:/shared/libmazewall_stack_agent.so=${agent_options}"
# The baseline arm loads no JVMTI agent, installs no marker, and never touches a collector.
[[ "${COVERAGE_BASELINE}" == 1 ]] && target_agent_flag=
target_class=AgentWorkload
target_classpath=/shared/agent-test-classes
target_args="gate:/shared/start platform 100 /shared/control.sock /shared/libmazewall_stack_agent.so $MARKER_OFFSET 4d5a defer-terminal /shared/target-host-pid"
if [[ "${SPLIT_COVERAGE}" == 1 ]]; then
    target_class=AgentCoverageWorkload
    target_args="gate:/shared/start /shared/coverage-oracle.json /shared/control.sock /shared/libmazewall_stack_agent.so $MARKER_OFFSET 4d5a defer-terminal /shared/target-host-pid"
fi
if [[ "${SPLIT_BENCHMARK}" == 1 ]]; then
    [[ "${SPLIT_COVERAGE}" == 0 ]] || { echo 'split benchmark and coverage cannot be combined' >&2; exit 2; }
    benchmark_mode=$([[ "${SPLIT_STACK_TRANSPORT}" == dictionary ]] && printf dictionary || printf inline)
    benchmark_mode+=$([[ "${SPLIT_EMISSION_MODE}" == full-stream ]] && printf -- -full-stream || printf -- -unique-edge)
    target_class=io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget
    target_classpath='/workspace/profiler/build/install/tier-e-daemon/lib/*'
    target_args="${benchmark_mode} ${BENCH_WARMUPS} ${BENCH_BATCHES} ${BENCH_ITERATIONS} /shared/start /shared/target-host-pid /shared/control.sock /shared/libmazewall_stack_agent.so ${MARKER_OFFSET} 4d5a /shared/benchmark-oracle.json"
fi
target_cpu_options=()
collector_cpu_options=()
[[ -n "${TIER_E_SPLIT_WORKLOAD_CPU:-}" ]] && target_cpu_options=(--cpuset-cpus "${TIER_E_SPLIT_WORKLOAD_CPU}")
[[ -n "${TIER_E_SPLIT_COLLECTOR_CPU:-}" ]] && collector_cpu_options=(--cpuset-cpus "${TIER_E_SPLIT_COLLECTOR_CPU}")
reuse_target_options=()
reuse_collector_options=()
real_reuse_target_options=()
[[ "${REAL_PID_REUSE}" == 1 ]] && real_reuse_target_options=(-e TIER_E_SPLIT_REAL_REUSE_READY=/shared/real-reuse-ready)
if [[ "${SIMULATED_PID_REUSE}" == 1 ]]; then
    reuse_target_options=(-e TIER_E_SPLIT_SIMULATE_PID_REUSE_MARKER=/shared/reused)
    reuse_collector_options=(-e TIER_E_BENCHMARK_REUSE_MARKER=/shared/reused)
fi
"${ENGINE}" run -d --name "${TARGET}" --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL "${target_cpu_options[@]}" \
    -e TIER_E_MARKER_OFFSET="${MARKER_OFFSET}" \
    -e TIER_E_COVERAGE_OPERATIONS="${SPLIT_COVERAGE_OPERATIONS}" \
    -e TIER_E_COVERAGE_SEED="${TIER_E_COVERAGE_SEED:-23063}" \
    -e JAVA_TOOL_OPTIONS="-Dmazewall.tier-e.workload-profile=${TIER_E_SPLIT_WORKLOAD_PROFILE:-syscall_dense}" \
    -e TIER_E_SPLIT_EMISSION_MODE="${SPLIT_EMISSION_MODE}" \
    -e TIER_E_SPLIT_MARKER_BACKEND="${SPLIT_MARKER_BACKEND}" \
    -e TIER_E_AGENT_OPTIONS="${agent_options}" \
    -e TIER_E_AGENT_FLAG="${target_agent_flag}" \
    -e TIER_E_SPLIT_COVERAGE_BASELINE="${COVERAGE_BASELINE}" \
    -e TIER_E_TARGET_CPU_OUT=target-cpu.json \
    -e TIER_E_TARGET_CLASS="${target_class}" -e TIER_E_TARGET_CLASSPATH="${target_classpath}" -e TIER_E_TARGET_ARGS="${target_args}" \
    "${real_reuse_target_options[@]}" \
    "${reuse_target_options[@]}" \
    -v "${SHARED}:/shared" -v "${ROOT}:/workspace:ro" -w /shared "${IMAGE}" bash -lc '
        exec java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
          ${TIER_E_AGENT_FLAG:-} \
          -cp "$TIER_E_TARGET_CLASSPATH" "$TIER_E_TARGET_CLASS" $TIER_E_TARGET_ARGS
    ' >/dev/null

# BPF pid_tgid values are host-global, so both registration and the privileged collector use
# the target's host PID namespace identity. The target receives it only after its start gate.
TARGET_HOST_PID=$("${ENGINE}" inspect -f '{{.State.Pid}}' "${TARGET}")
[[ "${TARGET_HOST_PID}" =~ ^[1-9][0-9]*$ ]] || { echo "target host pid is invalid: ${TARGET_HOST_PID}" >&2; exit 1; }
printf '%s\n' "${TARGET_HOST_PID}" > "${SHARED}/target-host-pid"
sed -i "s/^pid=.*/pid=${TARGET_HOST_PID}/" "${SHARED}/target.properties"
TARGET_STARTTIME=
if [[ "${SPLIT_COVERAGE}" == 1 ]]; then
    # Registration identity is captured while the target is still live; the exited target
    # is never probed again, so the CPU sample is compared against this saved value.
    TARGET_STARTTIME=$(awk '{print $22}' "/proc/${TARGET_HOST_PID}/stat")
    [[ "${TARGET_STARTTIME}" =~ ^[1-9][0-9]*$ ]] || { echo "target starttime is invalid: ${TARGET_STARTTIME}" >&2; exit 1; }
    sed -i "s/^process_incarnation=.*/process_incarnation=${TARGET_STARTTIME}/" "${SHARED}/target.properties"
fi
if [[ "${SPLIT_COVERAGE}" == 1 ]]; then
    for _ in $(seq 1 100); do [[ -s "${SHARED}/coverage-oracle.json" ]] && break; sleep 0.1; done
    test -s "${SHARED}/coverage-oracle.json"
    COVERAGE_ORACLE_SHA256=$(sha256sum "${SHARED}/coverage-oracle.json" | awk '{print $1}')
fi
if [[ "${COVERAGE_BASELINE}" == 1 ]]; then
    touch "${SHARED}/start"
    "${ENGINE}" wait "${TARGET}" >/dev/null
    TARGET_EXIT=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${TARGET}")
    "${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1
    cp "${SHARED}/coverage-oracle.json" "${ARTIFACT_DIR}/coverage-oracle.json"
    cp "${SHARED}/target-cpu.json" "${ARTIFACT_DIR}/target-cpu.json"
    node "${ROOT}/scripts/tier_e_target_cpu_sample.mjs" --validate "${ARTIFACT_DIR}" \
        --host-pid "${TARGET_HOST_PID}" --starttime "${TARGET_STARTTIME}"
    printf 'mode=baseline\ncoverage_verdict=NOT_APPLICABLE\nterminal=NOT_APPLICABLE\ntarget_exit=%s\npolicy_eligibility=false\n' \
        "${TARGET_EXIT}" > "${ARTIFACT_DIR}/verdict.env"
    printf '{"schema_version":1,"mode":"baseline","target_exit":%s,"policy_eligibility":false}\n' \
        "${TARGET_EXIT}" > "${ARTIFACT_DIR}/summary.json"
    grep -q 'tier-e-coverage-target: operations=' "${ARTIFACT_DIR}/target.log"
    [[ "${TARGET_EXIT}" == 0 ]] || exit 1
    printf 'split coverage baseline fixture artifacts: %s\n' "${ARTIFACT_DIR}"
    exit 0
fi

socket_access_options=()
[[ "${SPLIT_SOCKET_ACCESS}" == group ]] && socket_access_options=(-e "TIER_E_SOCKET_GID=${CALLER_GID}")
benchmark_record_options=()
if [[ "${SPLIT_BENCHMARK}" == 1 || ( "${SPLIT_COVERAGE}" == 1 && "${SPLIT_COVERAGE_OPERATIONS}" -gt 72 ) ]]; then
    benchmark_record_options=(-e TIER_E_BENCHMARK_RECORD_LIMIT=250000)
fi
"${ENGINE}" run -d --name "${COLLECTOR}" --privileged --pid=host --userns=host "${collector_cpu_options[@]}" \
    -v "${SHARED}:/shared" -v "${ROOT}:${ROOT}:ro" -w "${ROOT}" \
    -e TIER_E_SOCKET=/shared/control.sock "${socket_access_options[@]}" "${benchmark_record_options[@]}" \
    -e TIER_E_TARGET_REGISTRATION=/shared/target.properties -e TIER_E_DEFINITION_ROOT=/shared/definitions \
    -e TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE="${NEGATIVE_IDENTITY_CASE}" \
    -e TIER_E_EVIDENCE_ROOT=/shared/evidence \
    -e TIER_E_SPLIT_BENCHMARK="${TIER_E_SPLIT_COLLECTOR_BENCHMARK_MODE:-${SPLIT_BENCHMARK}}" \
    -e TIER_E_BENCHMARK_PHASES="${TIER_E_SPLIT_BENCHMARK_PHASES:-0}" \
    -e TIER_E_LIVE_SNAPSHOT_ADMISSION="${TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS:-0}" \
    -e TIER_E_MARKER_VERSION="${SPLIT_MARKER_VERSION}" \
    -e TIER_E_COVERAGE_RECOVERY_MODE="${SPLIT_RECOVERY_MODE}" \
    -e TIER_E_RECOVERY_MAP_CAPACITY="${TIER_E_SPLIT_RECOVERY_MAP_CAPACITY:-4096}" \
    ${TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL:+-e TIER_E_BENCHMARK_DROP_RING_SYSCALL="${TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL}"} \
    -e TIER_E_COLLECTOR_RECEIPT_CLOCK=$([[ "${TIER_E_SPLIT_BENCHMARK_PHASES:-0}" == 1 ]] && printf monotonic || printf disabled) \
    "${reuse_collector_options[@]}" \
    ${COVERAGE_ORACLE_SHA256:+-e TIER_E_COVERAGE_MANIFEST=1 -e TIER_E_COVERAGE_ORACLE_SHA256="${COVERAGE_ORACLE_SHA256}" -e TIER_E_COVERAGE_SYSCALL_NUMBER=257} \
    -e TIER_E_TERMINAL_ARTIFACT_WAIT_MILLIS=$([[ -n "${NEGATIVE_ARTIFACT_CASE}" ]] && printf 1000 || printf 30000) \
    "${IMAGE}" bash -lc '
        mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
        target_pid=$(sed -n "s/^pid=//p" /shared/target.properties)
        start=$(awk "{print \$22}" "/proc/${target_pid}/stat")
        if [[ "$TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE" == stale ]]; then start=$((start + 1)); fi
        sed -i "s/^process_incarnation=.*/process_incarnation=${start}/" /shared/target.properties
        exec profiler/build/install/tier-e-daemon/bin/tier-e-daemon
    ' >/dev/null

for _ in $(seq 1 100); do [[ -S "${SHARED}/control.sock" ]] && break; sleep 0.1; done
[[ -S "${SHARED}/control.sock" ]] || { "${ENGINE}" logs "${COLLECTOR}" >&2; exit 1; }
# The target is admitted through the explicitly configured group.  A distinct unprivileged
# identity must not even connect to the control socket; if it can, its SHUTDOWN request makes
# the fixture fail rather than silently widening the collector boundary.
if "${ENGINE}" run --rm --user 65534:65534 --cap-drop ALL \
    -v "${SHARED}:/shared" "${IMAGE}" \
    java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown \
    > "${ARTIFACT_DIR}/unrelated-user.log" 2>&1; then
    echo "unrelated user unexpectedly accessed Tier E control socket" >&2
    exit 1
fi
grep -Eq 'Permission denied|AccessDeniedException|ConnectException' "${ARTIFACT_DIR}/unrelated-user.log"
if [[ "${SPLIT_SOCKET_ACCESS}" == owner ]]; then
    if "${ENGINE}" run --rm --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL \
        -v "${SHARED}:/shared" "${IMAGE}" \
        java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown \
        > "${ARTIFACT_DIR}/target-user-denied.log" 2>&1; then
        echo "target UID unexpectedly accessed owner-only Tier E socket" >&2
        exit 1
    fi
    grep -Eq 'Permission denied|AccessDeniedException|ConnectException' "${ARTIFACT_DIR}/target-user-denied.log"
    "${ENGINE}" run --rm --user 0:0 -v "${SHARED}:/shared" "${IMAGE}" \
        java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown \
        > "${ARTIFACT_DIR}/control.log" 2>&1
    "${ENGINE}" wait "${COLLECTOR}" > /dev/null
    printf 'socket_access=OWNER_ONLY\ntarget_admission=DENIED\nunrelated_user_admission=DENIED\npolicy_eligibility=false\n' \
        > "${ARTIFACT_DIR}/verdict.env"
    echo "split owner-only socket fixture artifacts: ${ARTIFACT_DIR}"
    exit 0
fi
if [[ "${REAL_PID_REUSE}" == 1 ]]; then
    # Freeze the registered collector after ATTACH, then let the real target exit.
    # Otherwise the 100 ms watcher can observe the gap before the PID is reused.
    touch "${SHARED}/start"
    for _ in $(seq 1 200); do
        [[ -s "${SHARED}/real-reuse-ready" ]] && break
        sleep 0.05
    done
    test -s "${SHARED}/real-reuse-ready"
    test "$("${ENGINE}" inspect -f '{{.State.Running}}' "${TARGET}")" == true
    registered_start=$(awk '{print $22}' "/proc/${TARGET_HOST_PID}/stat")
    test "${registered_start}" == "$(sed -n 's/^process_incarnation=//p' "${SHARED}/target.properties")"
    "${ENGINE}" pause "${COLLECTOR}" >/dev/null
    "${ENGINE}" kill "${TARGET}" >/dev/null
    "${ENGINE}" wait "${TARGET}" >/dev/null
    target_exit=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${TARGET}")
    test "${target_exit}" == 137
    python3 "${ROOT}/scripts/probe_tier_e_real_pid_reuse.py" \
        --registered-pid "${TARGET_HOST_PID}" --registered-starttime "${registered_start}" \
        --max-attempts 8192 --deadline-seconds 75 --hold-seconds 30 \
        --output "${ARTIFACT_DIR}/real-pid-reuse-probe.json" > "${ARTIFACT_DIR}/probe.log" 2>&1 &
    probe_pid=$!
    for _ in $(seq 1 1600); do
        [[ -s "${ARTIFACT_DIR}/real-pid-reuse-probe.json" ]] && break
        kill -0 "${probe_pid}" 2>/dev/null || break
        sleep 0.05
    done
    test -s "${ARTIFACT_DIR}/real-pid-reuse-probe.json"
    if ! node -e 'const p=require(process.argv[1]);process.exit(p.host_pid_reuse_verdict==="OBSERVED"?0:1)' \
        "${ARTIFACT_DIR}/real-pid-reuse-probe.json"; then
        printf 'host_pid_reuse_verdict=UNVERIFIED\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
        exit 1
    fi
    # Check the replacement is still the live process observed by the probe.
    replacement_start=$(awk '{print $22}' "/proc/${TARGET_HOST_PID}/stat")
    node -e 'const p=require(process.argv[1]);if(p.replacement.starttime!==Number(process.argv[2]))process.exit(1)' \
        "${ARTIFACT_DIR}/real-pid-reuse-probe.json" "${replacement_start}"
    "${ENGINE}" unpause "${COLLECTOR}" >/dev/null
    for _ in $(seq 1 200); do
        [[ -f "${SHARED}/evidence/split-fixture-19802.tier-e.json" ]] && break
        sleep 0.1
    done
    test -f "${SHARED}/evidence/split-fixture-19802.tier-e.json"
    test "$(awk '{print $22}' "/proc/${TARGET_HOST_PID}/stat")" == "${replacement_start}"
    "${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
        cat /shared/evidence/split-fixture-19802.tier-e.json > "${ARTIFACT_DIR}/terminal-report.json"
    node -e '
        const fs=require("fs"),r=JSON.parse(fs.readFileSync(process.argv[1]));
        if(r.terminal_reason!=="PROCESS_IDENTITY_REUSED"||r.evidence_integrity!=="INCOMPLETE"||
           r.lifecycle_validity!==false||r.transport_completeness!==false||r.policy_eligibility!==false)
            process.exit(1);
    ' "${ARTIFACT_DIR}/terminal-report.json"
    "${ENGINE}" run --rm --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL \
        -v "${SHARED}:/shared" "${IMAGE}" \
        java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown \
        > "${ARTIFACT_DIR}/control.log" 2>&1
    "${ENGINE}" wait "${COLLECTOR}" >/dev/null
    test "$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${COLLECTOR}")" == 0
    wait "${probe_pid}"
    probe_pid=
    cp "${SHARED}/definitions/orders.marker-proof" "${ARTIFACT_DIR}/marker-version-proof.txt"
    printf 'host_pid_reuse_verdict=OBSERVED\nterminal_reason=PROCESS_IDENTITY_REUSED\nevidence_integrity=INCOMPLETE\ntarget_exit=%s\npolicy_eligibility=false\n' \
        "${target_exit}" > "${ARTIFACT_DIR}/verdict.env"
    printf 'split real guest host PID-reuse fixture artifacts: %s\n' "${ARTIFACT_DIR}"
    exit 0
fi
touch "${SHARED}/start"
"${ENGINE}" wait "${TARGET}" >/dev/null
if [[ "${DELAYED_ARTIFACT}" == 1 ]]; then
    sleep 2
    cp "${SHARED}/definitions/orders.mzse" "${SHARED}/definitions/delayed.mzse"
fi
terminal_wait_attempts=300
[[ "${SPLIT_COVERAGE}" == 1 ]] && terminal_wait_attempts=900
for _ in $(seq 1 "${terminal_wait_attempts}"); do [[ -f "${SHARED}/evidence/split-fixture-19802.tier-e.json" ]] && break; sleep 0.1; done
test -f "${SHARED}/evidence/split-fixture-19802.tier-e.json"
if [[ "${SPLIT_COVERAGE}" == 1 ]]; then
    # The target has exited; only the live collector is sampled, strictly after its
    # terminal report and before SHUTDOWN. A missing or unstable source is INCOMPLETE.
    collector_host_pid=$("${ENGINE}" inspect -f '{{.State.Pid}}' "${COLLECTOR}")
    collector_running=$("${ENGINE}" inspect -f '{{.State.Running}}' "${COLLECTOR}")
    if [[ "${collector_running}" != true || ! "${collector_host_pid}" =~ ^[1-9][0-9]*$ ]] ||
        ! node "${ROOT}/scripts/tier_e_collector_cpu_sample.mjs" \
            --pid "${collector_host_pid}" --hz "$(getconf CLK_TCK)" \
            --boundary after_terminal_report_before_shutdown \
            --out "${ARTIFACT_DIR}/collector-process-metrics.json"; then
        printf 'collector_cpu_sample=INCOMPLETE\nevidence_integrity=INCOMPLETE\npolicy_eligibility=false\n' \
            > "${ARTIFACT_DIR}/verdict.env"
        echo 'collector CPU sample is INCOMPLETE' >&2
        exit 1
    fi
fi
if [[ "${SPLIT_BENCHMARK}" == 1 ]]; then
    collector_host_pid=$("${ENGINE}" inspect -f '{{.State.Pid}}' "${COLLECTOR}")
    node - "${collector_host_pid}" "$(getconf CLK_TCK)" "${ARTIFACT_DIR}/collector-process-metrics.json" <<'NODE'
const fs=require('fs');
const [pid,hz,out]=process.argv.slice(2);
const status=fs.readFileSync(`/proc/${pid}/status`,'utf8');
const stat=fs.readFileSync(`/proc/${pid}/stat`,'utf8');
const fields=stat.slice(stat.lastIndexOf(')')+2).trim().split(/\s+/);
const ticks=Number(fields[11])+Number(fields[12]);
const hwm=Number(/^VmHWM:\s+(\d+)\s+kB/m.exec(status)?.[1]??-1);
const result={schema_version:1,collector_host_pid:Number(pid),cpu_ticks:ticks,cpu_ns:Math.round(ticks*1e9/Number(hz)),peak_rss_kb:hwm,
  cpu_time_basis:'process_ticks_since_start',sample_boundary:'after_terminal_report_before_shutdown'};
fs.writeFileSync(out,JSON.stringify(result)+'\n');
NODE
fi
"${ENGINE}" run --rm --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL \
    -v "${SHARED}:/shared" "${IMAGE}" \
    java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown > "${ARTIFACT_DIR}/control.log" 2>&1
"${ENGINE}" wait "${COLLECTOR}" >/dev/null
TARGET_EXIT=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${TARGET}")
COLLECTOR_EXIT=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${COLLECTOR}")
if [[ -f "${SHARED}/definitions/orders.marker-proof" ]]; then
    cp "${SHARED}/definitions/orders.marker-proof" "${ARTIFACT_DIR}/marker-version-proof.txt"
fi
if [[ "${TARGET_EXIT}" != 0 || "${COLLECTOR_EXIT}" != 0 ]]; then
    if [[ "${SPLIT_MARKER_VERSION}" == v2 && "${COLLECTOR_EXIT}" == 0 && "${TARGET_EXIT}" != 0 &&
          ( "${agent_marker_version}" != v2 || "${TIER_E_SPLIT_DISABLE_MARKER_PROOF:-0}" == 1 ) ]]; then
        "${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1
        "${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1
        "${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
            cat /shared/evidence/split-fixture-19802.tier-e.json > "${ARTIFACT_DIR}/terminal-report.json"
        node - "${ARTIFACT_DIR}" <<'NODE'
const fs=require('fs'),d=process.argv[2],r=JSON.parse(fs.readFileSync(`${d}/terminal-report.json`));
const target=fs.readFileSync(`${d}/target.log`,'utf8');
if (r.terminal_reason!=='MARKER_VERSION_MISMATCH'||r.evidence_integrity!=='INCOMPLETE'||
    r.resolved_syscalls!==0||r.lifecycle_validity!==false||r.policy_eligibility!==false||
    !target.includes('attach failed')||target.includes('tier-e-coverage-target: operations=')) process.exit(1);
NODE
        printf 'marker_mismatch=REJECTED_BEFORE_WORKLOAD\nevidence_integrity=INCOMPLETE\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
        exit 0
    fi
    if [[ "${NEGATIVE_IDENTITY_CASE}" == stale && "${TARGET_EXIT}" != 0 && "${COLLECTOR_EXIT}" == 0 ]]; then
        "${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1
        "${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1
        "${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
            cat /shared/evidence/split-fixture-19802.tier-e.json > "${ARTIFACT_DIR}/terminal-report.json"
        node -e '
            const fs=require("fs"),r=JSON.parse(fs.readFileSync(process.argv[1]));
            if(r.evidence_integrity!=="INCOMPLETE" || r.terminal_reason!=="STALE_PROCESS_IDENTITY" ||
               r.lifecycle_validity!==false || r.policy_eligibility!==false || r.resolved_syscalls!==0) process.exit(1);
        ' "${ARTIFACT_DIR}/terminal-report.json"
        printf 'evidence_integrity=INCOMPLETE\nterminal_reason=STALE_PROCESS_IDENTITY\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
        printf 'split stale-identity fixture artifacts: %s\n' "${ARTIFACT_DIR}"
        exit 0
    fi
    printf 'capture_domain_validity=INCOMPLETE\ntransport_completeness=INCOMPLETE\nattribution_coverage=INCOMPLETE\ndictionary_integrity=UNVERIFIED\nlifecycle_validity=INCOMPLETE\npolicy_eligibility=false\ntarget_exit=%s\ncollector_exit=%s\n' "${TARGET_EXIT}" "${COLLECTOR_EXIT}" > "${ARTIFACT_DIR}/verdict.env"
    exit 1
fi
"${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1
"${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1
if [[ "${SPLIT_BENCHMARK}" == 1 ]]; then
    cp "${SHARED}/benchmark-oracle.json" "${ARTIFACT_DIR}/benchmark-oracle.json"
    "${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
        cat /shared/evidence/split-fixture-19802.tier-e-operations.json > "${ARTIFACT_DIR}/benchmark-operations.json"
fi
"${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
    cat /shared/evidence/split-fixture-19802.tier-e.json > "${ARTIFACT_DIR}/terminal-report.json"
if [[ "${SIMULATED_PID_REUSE}" == 1 ]]; then
    node -e '
        const fs=require("fs"),r=JSON.parse(fs.readFileSync(process.argv[1]));
        if(r.evidence_integrity!=="INCOMPLETE" || r.terminal_reason!=="PROCESS_IDENTITY_REUSED" ||
           r.lifecycle_validity!==false || r.transport_completeness!==false || r.policy_eligibility!==false)
           process.exit(1);
    ' "${ARTIFACT_DIR}/terminal-report.json"
    printf 'evidence_integrity=INCOMPLETE\nterminal_reason=PROCESS_IDENTITY_REUSED\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
    printf 'split simulated PID-reuse fixture artifacts: %s\n' "${ARTIFACT_DIR}"
    exit 0
fi
if [[ "${SPLIT_COVERAGE}" == 1 ]]; then
    if [[ -f "${SHARED}/definitions/orders.mzse" ]]; then
        cp "${SHARED}/definitions/orders.mzse" "${ARTIFACT_DIR}/orders.mzse"
    fi
    if [[ -f "${SHARED}/evidence/split-fixture-19802.tier-e-coverage.json" ]]; then
        "${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
            cat /shared/evidence/split-fixture-19802.tier-e-coverage.json > "${ARTIFACT_DIR}/coverage-manifest.json"
    fi
    cp "${SHARED}/coverage-oracle.json" "${ARTIFACT_DIR}/coverage-oracle.json"
    cp "${SHARED}/target-cpu.json" "${ARTIFACT_DIR}/target-cpu.json"
    node "${ROOT}/scripts/tier_e_target_cpu_sample.mjs" --validate "${ARTIFACT_DIR}" \
        --host-pid "${TARGET_HOST_PID}" --starttime "${TARGET_STARTTIME}"
    node "${ROOT}/scripts/verify_tier_e_split_coverage.mjs" \
        "${ARTIFACT_DIR}/coverage-oracle.json" "${ARTIFACT_DIR}/coverage-manifest.json" \
        "${ARTIFACT_DIR}/terminal-report.json" "${ARTIFACT_DIR}/coverage-verdict.json" || {
            [[ "${ALLOW_INCOMPLETE}" == 1 ]] || exit 1
        }
fi
if [[ "${agent_marker_version}" != "${SPLIT_MARKER_VERSION}" ]]; then
    node - "${ARTIFACT_DIR}" <<'NODE'
const fs=require('fs'),dir=process.argv[2];
const t=JSON.parse(fs.readFileSync(`${dir}/terminal-report.json`));
const c=JSON.parse(fs.readFileSync(`${dir}/coverage-verdict.json`));
if(t.evidence_integrity!=='INCOMPLETE'||t.lifecycle_validity!==false||
   c.coverage_verdict==='PASS'||t.policy_eligibility!==false)process.exit(1);
NODE
    printf 'marker_mismatch=REJECTED\nevidence_integrity=INCOMPLETE\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
    exit 0
fi
grep -q 'tier-e-workflow: capture complete; terminal control deferred' "${ARTIFACT_DIR}/target.log"
grep -q 'tier-e-control: shutdown' "${ARTIFACT_DIR}/control.log"
grep -Eq 'active_polls=[1-9][0-9]*' "${ARTIFACT_DIR}/collector.log" "${ARTIFACT_DIR}/target.log"
grep -Eq 'resolved_syscalls=[1-9][0-9]*' "${ARTIFACT_DIR}/collector.log"
if [[ -n "${TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL:-}" ]]; then
    node - "${ARTIFACT_DIR}" <<'NODE'
const fs=require('fs'),dir=process.argv[2];
const terminal=JSON.parse(fs.readFileSync(`${dir}/terminal-report.json`));
const coverage=JSON.parse(fs.readFileSync(`${dir}/coverage-verdict.json`));
const manifest=JSON.parse(fs.readFileSync(`${dir}/coverage-manifest.json`));
const singletons=manifest.edges.filter(edge=>edge.workload_path>=48 && edge.workload_path<=71);
if (terminal.evidence_integrity!=='INCOMPLETE' || terminal.transport_completeness!==false ||
    terminal.attribution_coverage!==false || terminal.lifecycle_validity!==true ||
    terminal.recovery_evidence?.insertion_failures!==0 || terminal.recovery_evidence?.recovered_edges<24 ||
    terminal.recovery_evidence?.confirmed_ring_loss_recoveries<24 ||
    coverage.coverage_verdict!=='PASS' || coverage.wrong_edges?.length ||
    singletons.length!==24 || singletons.some(edge=>edge.discovery_source!=='map') ||
    terminal.policy_eligibility!==false) process.exit(1);
NODE
    printf 'evidence_integrity=INCOMPLETE\ncoverage_verdict=PASS\nring_transport=INCOMPLETE\nsingleton_source=MAP\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
elif [[ "${ALLOW_INCOMPLETE}" == 1 ]]; then
    node -e '
        const fs=require("fs"), p=process.argv[1];
        const terminal=JSON.parse(fs.readFileSync(`${p}/terminal-report.json`));
        const coverage=JSON.parse(fs.readFileSync(`${p}/coverage-verdict.json`));
        const complete=terminal.evidence_integrity === "COMPLETE" && coverage.coverage_verdict === "PASS";
        const bounded=terminal.evidence_integrity === "INCOMPLETE" && coverage.coverage_verdict === "INCOMPLETE";
        if (terminal.policy_eligibility !== false || (!complete && !bounded) ||
            coverage.policy_eligibility !== false || coverage.coverage_verdict === "FAILED" ||
            coverage.wrong_edges?.length) process.exit(1);
    ' "${ARTIFACT_DIR}"
    node -e 'const fs=require("fs"),p=process.argv[1],t=JSON.parse(fs.readFileSync(`${p}/terminal-report.json`)),c=JSON.parse(fs.readFileSync(`${p}/coverage-verdict.json`));fs.writeFileSync(`${p}/verdict.env`,`evidence_integrity=${t.evidence_integrity}\ncoverage_verdict=${c.coverage_verdict}\npolicy_eligibility=false\n`)' "${ARTIFACT_DIR}"
elif [[ -n "${NEGATIVE_ARTIFACT_CASE}" ]]; then
    grep -q 'evidence_integrity=INCOMPLETE' "${ARTIFACT_DIR}/collector.log"
    grep -q '"evidence_integrity":"INCOMPLETE"' "${ARTIFACT_DIR}/terminal-report.json"
    grep -q '"policy_eligibility":false' "${ARTIFACT_DIR}/terminal-report.json"
    grep -Eq '"failed_dimensions":\[[^]]+\]' "${ARTIFACT_DIR}/terminal-report.json"
    printf 'evidence_integrity=INCOMPLETE\nnegative_artifact_case=%s\npolicy_eligibility=false\n' "${NEGATIVE_ARTIFACT_CASE}" > "${ARTIFACT_DIR}/verdict.env"
else
    test -s "${SHARED}/definitions/orders.mzse"
    cp "${SHARED}/definitions/orders.mzse" "${ARTIFACT_DIR}/orders.mzse"
    grep -q 'evidence_integrity=COMPLETE' "${ARTIFACT_DIR}/collector.log"
    grep -q 'capture_domain_validity=true' "${ARTIFACT_DIR}/collector.log"
    grep -q 'transport_completeness=true' "${ARTIFACT_DIR}/collector.log"
    grep -q 'attribution_coverage=true' "${ARTIFACT_DIR}/collector.log"
    grep -q 'dictionary_integrity=true' "${ARTIFACT_DIR}/collector.log"
    grep -q 'lifecycle_validity=true' "${ARTIFACT_DIR}/collector.log"
    grep -q '"evidence_integrity":"COMPLETE"' "${ARTIFACT_DIR}/terminal-report.json"
    printf 'evidence_integrity=COMPLETE\ncapture_domain_validity=PASS\ntransport_completeness=PASS\nattribution_coverage=PASS\ndictionary_integrity=PASS\nlifecycle_validity=PASS\npolicy_eligibility=false\n' > "${ARTIFACT_DIR}/verdict.env"
fi
printf 'emission_mode=%s\nstack_transport=%s\n' "${SPLIT_EMISSION_MODE}" "${SPLIT_STACK_TRANSPORT}" >> "${ARTIFACT_DIR}/verdict.env"
printf 'split privilege fixture artifacts: %s\n' "${ARTIFACT_DIR}"

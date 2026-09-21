#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
ARTIFACT_DIR=${TIER_E_SPLIT_ARTIFACT_DIR:-"${ROOT}/build/tier-e-split-privilege"}
RUN_ID="tier-e-split-$$"
SHARED=$(mktemp -d)
TARGET="${RUN_ID}-target"
COLLECTOR="${RUN_ID}-collector"
CALLER_UID=$(id -u)
CALLER_GID=$(id -g)
NATIVE_BUILD_DIR=${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}
NEGATIVE_ARTIFACT_CASE=${TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE:-}

cleanup() {
    mkdir -p "${ARTIFACT_DIR}"
    "${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1 || true
    "${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1 || true
    "${ENGINE}" rm -f "${TARGET}" "${COLLECTOR}" >/dev/null 2>&1 || true
    rm -rf "${SHARED}"
}
trap cleanup EXIT

[[ "${ENGINE}" == docker ]] || { echo "rootless runtimes are INCOMPLETE evidence" >&2; exit 2; }
export DOCKER_HOST
mkdir -p "${ARTIFACT_DIR}" "${SHARED}/definitions" "${SHARED}/evidence"
# Keep the mount root traversable so the unrelated-user probe reaches connect(2).
# Definitions and operator evidence remain inaccessible outside the target group.
chmod 0755 "${SHARED}"
chmod 0770 "${SHARED}/definitions" "${SHARED}/evidence"

registered_artifact_file=orders.mzse
case "${NEGATIVE_ARTIFACT_CASE}" in
    "") ;;
    missing) registered_artifact_file=missing.mzse ;;
    malformed)
        registered_artifact_file=malformed.mzse
        printf 'not-an-mzse-envelope' > "${SHARED}/definitions/${registered_artifact_file}"
        ;;
    symlink)
        registered_artifact_file=symlink.mzse
        ln -s orders.mzse "${SHARED}/definitions/${registered_artifact_file}"
        ;;
    *) echo "unknown TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE: ${NEGATIVE_ARTIFACT_CASE}" >&2; exit 2 ;;
esac

"${ROOT}/gradlew" :profiler:installDist >/dev/null
if [[ ! -f "${NATIVE_BUILD_DIR}/CMakeCache.txt" ]]; then
    cmake -S "${ROOT}/profiler-native" -B "${NATIVE_BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release >/dev/null
fi
cmake --build "${NATIVE_BUILD_DIR}" -j2 >/dev/null
MARKER_OFFSET_RAW=$(nm -D --defined-only "${NATIVE_BUILD_DIR}/libmazewall_stack_agent.so" | awk '$3 == "mazewall_stack_marker" { print $1; exit }')
[[ -n "${MARKER_OFFSET_RAW}" ]] || { echo "missing mazewall_stack_marker symbol" >&2; exit 1; }
MARKER_OFFSET=$(printf '0x%x' "$((16#${MARKER_OFFSET_RAW}))")
"${ENGINE}" build -t "${IMAGE}" -f "${ROOT}/infra/dev/Containerfile" "${ROOT}" >/dev/null

cp "${NATIVE_BUILD_DIR}/libmazewall_stack_agent.so" "${SHARED}/libmazewall_stack_agent.so"
cp -R "${NATIVE_BUILD_DIR}/agent-test-classes" "${SHARED}/agent-test-classes"
cat > "${SHARED}/target.properties" <<EOF
version=1
registration_id=split-fixture
pid=1
session_tag=4d5a
process_incarnation=1
marker_path=/shared/libmazewall_stack_agent.so
marker_offset=${MARKER_OFFSET}
artifact_file=${registered_artifact_file}
EOF

# The collector's PID namespace is joined to the target. Linux reports the target JVM as PID 1.
# The fixture rewrites process_incarnation after start with its in-namespace /proc starttime.
"${ENGINE}" run -d --name "${TARGET}" --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL \
    -e TIER_E_MARKER_OFFSET="${MARKER_OFFSET}" \
    -v "${SHARED}:/shared" "${IMAGE}" bash -lc '
        exec java -agentpath:/shared/libmazewall_stack_agent.so=session=4d5a,session-bound-definitions,definitions=/shared/definitions/orders.mzse \
          -cp /shared/agent-test-classes AgentWorkload gate:/shared/start platform 100 /shared/control.sock /shared/libmazewall_stack_agent.so "$TIER_E_MARKER_OFFSET" 4d5a defer-terminal /shared/target-host-pid
    ' >/dev/null

# BPF pid_tgid values are host-global, so both registration and the privileged collector use
# the target's host PID namespace identity. The target receives it only after its start gate.
TARGET_HOST_PID=$("${ENGINE}" inspect -f '{{.State.Pid}}' "${TARGET}")
[[ "${TARGET_HOST_PID}" =~ ^[1-9][0-9]*$ ]] || { echo "target host pid is invalid: ${TARGET_HOST_PID}" >&2; exit 1; }
printf '%s\n' "${TARGET_HOST_PID}" > "${SHARED}/target-host-pid"
sed -i "s/^pid=.*/pid=${TARGET_HOST_PID}/" "${SHARED}/target.properties"

"${ENGINE}" run -d --name "${COLLECTOR}" --privileged --pid=host --userns=host \
    -v "${SHARED}:/shared" -v "${ROOT}:${ROOT}:ro" -w "${ROOT}" \
    -e TIER_E_SOCKET=/shared/control.sock -e TIER_E_SOCKET_GID="${CALLER_GID}" \
    -e TIER_E_TARGET_REGISTRATION=/shared/target.properties -e TIER_E_DEFINITION_ROOT=/shared/definitions \
    -e TIER_E_EVIDENCE_ROOT=/shared/evidence \
    -e TIER_E_TERMINAL_ARTIFACT_WAIT_MILLIS=$([[ -n "${NEGATIVE_ARTIFACT_CASE}" ]] && printf 1000 || printf 30000) \
    "${IMAGE}" bash -lc '
        mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing
        target_pid=$(sed -n "s/^pid=//p" /shared/target.properties)
        start=$(awk "{print \$22}" "/proc/${target_pid}/stat")
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
touch "${SHARED}/start"
"${ENGINE}" wait "${TARGET}" >/dev/null
for _ in $(seq 1 300); do [[ -f "${SHARED}/evidence/split-fixture-19802.tier-e.json" ]] && break; sleep 0.1; done
test -f "${SHARED}/evidence/split-fixture-19802.tier-e.json"
"${ENGINE}" run --rm --user "${CALLER_UID}:${CALLER_GID}" --cap-drop ALL \
    -v "${SHARED}:/shared" "${IMAGE}" \
    java -cp /shared/agent-test-classes AgentWorkload control /shared/control.sock shutdown > "${ARTIFACT_DIR}/control.log" 2>&1
"${ENGINE}" wait "${COLLECTOR}" >/dev/null
TARGET_EXIT=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${TARGET}")
COLLECTOR_EXIT=$("${ENGINE}" inspect -f '{{.State.ExitCode}}' "${COLLECTOR}")
if [[ "${TARGET_EXIT}" != 0 || "${COLLECTOR_EXIT}" != 0 ]]; then
    printf 'capture_domain_validity=INCOMPLETE\ntransport_completeness=INCOMPLETE\nattribution_coverage=INCOMPLETE\ndictionary_integrity=UNVERIFIED\nlifecycle_validity=INCOMPLETE\npolicy_eligibility=false\ntarget_exit=%s\ncollector_exit=%s\n' "${TARGET_EXIT}" "${COLLECTOR_EXIT}" > "${ARTIFACT_DIR}/verdict.env"
    exit 1
fi
"${ENGINE}" logs "${TARGET}" > "${ARTIFACT_DIR}/target.log" 2>&1
"${ENGINE}" logs "${COLLECTOR}" > "${ARTIFACT_DIR}/collector.log" 2>&1
"${ENGINE}" run --rm -v "${SHARED}:/shared:ro" "${IMAGE}" \
    cat /shared/evidence/split-fixture-19802.tier-e.json > "${ARTIFACT_DIR}/terminal-report.json"
grep -q 'tier-e-workflow: capture complete; terminal control deferred' "${ARTIFACT_DIR}/target.log"
grep -q 'tier-e-control: shutdown' "${ARTIFACT_DIR}/control.log"
grep -Eq 'active_polls=[1-9][0-9]*' "${ARTIFACT_DIR}/target.log"
grep -Eq 'resolved_syscalls=[1-9][0-9]*' "${ARTIFACT_DIR}/collector.log"
if [[ -n "${NEGATIVE_ARTIFACT_CASE}" ]]; then
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
printf 'split privilege fixture artifacts: %s\n' "${ARTIFACT_DIR}"

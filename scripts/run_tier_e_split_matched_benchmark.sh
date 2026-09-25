#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
OUT=${TIER_E_SPLIT_MATCHED_OUT:-"${ROOT}/build/tier-e-split-matched"}
FORKS=${TIER_E_SPLIT_MATCHED_FORKS:-5}
ITERATIONS=${TIER_E_SPLIT_MATCHED_ITERATIONS:-200}
PROFILE=${TIER_E_SPLIT_WORKLOAD_PROFILE:-syscall_dense}
WORKLOAD_CPU=${TIER_E_SPLIT_WORKLOAD_CPU:-0}
COLLECTOR_CPU=${TIER_E_SPLIT_COLLECTOR_CPU:-1}
CHILD_TIMEOUT_SEC=${TIER_E_SPLIT_CHILD_TIMEOUT_SEC:-540}
FIXTURE_TIMEOUT_SEC=${TIER_E_SPLIT_FIXTURE_TIMEOUT_SEC:-600}
TIMEOUT_KILL_AFTER_SEC=${TIER_E_SPLIT_TIMEOUT_KILL_AFTER_SEC:-30}
CAPTURE_PROFILE=${TIER_E_SPLIT_CAPTURE_PROFILE:-full}
ATTACHMENT_BACKEND=${TIER_E_SPLIT_ATTACHMENT_BACKEND:-auto}
# Even forks run Tier E arms instrumented (native phases + collector receipt);
# odd forks run them uninstrumented so workload timing stays free of
# phase-clock overhead. Baseline and USER_NOTIF arms are never instrumented.
INSTRUMENTED_FORK_PARITY=${TIER_E_SPLIT_INSTRUMENTED_FORK_PARITY:-even}
RUN_ID="tier-e-split-matched-$$"
export TIER_E_SPLIT_RUN_ID="${RUN_ID}"
MODES=(baseline dictionary-full-stream dictionary-unique-edge inline-full-stream inline-unique-edge user-notif-no-stack user-notif-stack)
mkdir -p "${OUT}"
if [[ "${ENGINE}" != docker || "${FORKS}" -lt 1 || "${ITERATIONS}" -lt 1 ]]; then
    echo 'invalid matched benchmark configuration' >&2
    exit 2
fi
if [[ -n "${TIER_E_SPLIT_MATCHED_MODES:-}" ]]; then
    IFS=, read -r -a MODES <<< "${TIER_E_SPLIT_MATCHED_MODES}"
fi
cleanup_matched() {
    # Only containers created by this run share its run-id prefix; never reap
    # unrelated containers that happen to match a generic name filter.
    "${ENGINE}" ps -q --filter "name=${RUN_ID}" 2>/dev/null | xargs -r "${ENGINE}" rm -f >/dev/null 2>&1 || true
}
trap cleanup_matched EXIT INT TERM
"${ENGINE}" image inspect "${IMAGE}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
"${ENGINE}" image inspect "${IMAGE}" --format '{{json .RepoDigests}}' > "${OUT}/image-digest.txt" || exit 1
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" > "${OUT}/source-state-before.json" || exit 1
uname -a > "${OUT}/kernel.txt"
NATIVE_SO="${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so"
NATIVE_HASH=$(sha256sum "${NATIVE_SO}" 2>/dev/null | awk '{print $1}')
if [[ -z "${NATIVE_HASH}" ]]; then NATIVE_HASH="unavailable:${NATIVE_SO}"; fi
JAR_HASH=$(sha256sum "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar 2>/dev/null | sort -k2 | sha256sum | awk '{print $1}')
JAR_COUNT=$(ls "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar 2>/dev/null | wc -l)
if [[ -z "${JAR_HASH}" ]]; then JAR_HASH="unavailable"; fi
EXPECTED_MEASURED_OPERATIONS=$((10 * ITERATIONS))
printf '{"schema_version":3,"forks":%s,"warmups":3,"measured_batches":10,"iterations_per_batch":%s,"expected_measured_operations":%s,"workload_profile":"%s","target_cpu":"%s","collector_cpu":"%s","capture_profile":"%s","attachment_backend":"%s","instrumented_fork_parity":"%s","child_timeout_sec":%s,"fixture_timeout_sec":%s,"timeout_kill_after_sec":%s,"native_agent_so_sha256":"%s","daemon_jars_sha256":"%s","daemon_jar_count":%s,"policy_eligibility":false}\n' \
    "${FORKS}" "${ITERATIONS}" "${EXPECTED_MEASURED_OPERATIONS}" "${PROFILE}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}" "${CAPTURE_PROFILE}" "${ATTACHMENT_BACKEND}" "${INSTRUMENTED_FORK_PARITY}" "${CHILD_TIMEOUT_SEC}" "${FIXTURE_TIMEOUT_SEC}" "${TIMEOUT_KILL_AFTER_SEC}" "${NATIVE_HASH}" "${JAR_HASH}" "${JAR_COUNT}" > "${OUT}/run-manifest.json"
: > "${OUT}/fork-results.jsonl"
failures=0
for ((fork=0; fork<FORKS; fork++)); do
    for ((step=0; step<${#MODES[@]}; step++)); do
        mode=${MODES[$(((fork + step) % ${#MODES[@]}))]}
        fork_dir="${OUT}/fork-${fork}-${mode}"
        mkdir -p "${fork_dir}"
        printf 'split matched fork=%s mode=%s\n' "${fork}" "${mode}"
        status=0
        case "${mode}" in
            baseline|user-notif-no-stack|user-notif-stack)
                timeout --signal=TERM --kill-after="${TIMEOUT_KILL_AFTER_SEC}s" "${CHILD_TIMEOUT_SEC}s" "${ENGINE}" run --rm --name "${RUN_ID}-baseline-${fork}-${mode}" --user "$(id -u):$(id -g)" --cap-drop ALL --cpuset-cpus "${WORKLOAD_CPU}" \
                    -e JAVA_TOOL_OPTIONS="-Dmazewall.tier-e.workload-profile=${PROFILE}" \
                    -v "${ROOT}:/workspace:ro" -w /tmp "${IMAGE}" \
                    java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
                    -cp '/workspace/profiler/build/install/tier-e-daemon/lib/*' \
                    io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget \
                    "${mode}" 3 10 "${ITERATIONS}" > "${fork_dir}/target.log" 2>&1 || status=$?
                if [[ "${status}" -eq 124 ]]; then printf 'child_timeout_sec=%s\n' "${CHILD_TIMEOUT_SEC}" > "${fork_dir}/timeout.env"; fi
                ;;
            dictionary-full-stream|dictionary-unique-edge|inline-full-stream|inline-unique-edge|dictionary-full-stream-v1|dictionary-full-stream-v2|dictionary-full-stream-hybrid|dictionary-unique-edge-v1|dictionary-unique-edge-v2|dictionary-unique-edge-hybrid)
                transport=dictionary
                [[ "${mode}" == inline-* ]] && transport=inline-per-invocation
                emission=full-stream
                [[ "${mode}" == *unique-edge* ]] && emission=unique-stack-syscall
                marker_version=v1
                recovery_mode=disabled
                [[ "${mode}" == *-v2 || "${mode}" == *-hybrid ]] && marker_version=v2
                [[ "${mode}" == *-hybrid ]] && recovery_mode=recover
                instrumented=0
                if [[ "${INSTRUMENTED_FORK_PARITY}" == even && $((fork % 2)) == 0 ]] || [[ "${INSTRUMENTED_FORK_PARITY}" == odd && $((fork % 2)) == 1 ]] || [[ "${INSTRUMENTED_FORK_PARITY}" == all ]]; then
                    instrumented=1
                fi
                printf 'instrumented=%s\n' "${instrumented}" > "${fork_dir}/instrumented.env"
                timeout --signal=TERM --kill-after="${TIMEOUT_KILL_AFTER_SEC}s" "${FIXTURE_TIMEOUT_SEC}s" env TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_BENCHMARK=1 \
                    TIER_E_SPLIT_BENCHMARK_PHASES="${instrumented}" \
                    TIER_E_SPLIT_MARKER_VERSION="${marker_version}" TIER_E_SPLIT_RECOVERY_MODE="${recovery_mode}" \
                    TIER_E_SPLIT_BENCH_ITERATIONS="${ITERATIONS}" \
                    TIER_E_SPLIT_WORKLOAD_PROFILE="${PROFILE}" \
                    TIER_E_SPLIT_STACK_TRANSPORT="${transport}" TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                    TIER_E_SPLIT_WORKLOAD_CPU="${WORKLOAD_CPU}" TIER_E_SPLIT_COLLECTOR_CPU="${COLLECTOR_CPU}" \
                    TIER_E_SPLIT_ARTIFACT_DIR="${fork_dir}" \
                    bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${fork_dir}/fixture.log" 2>&1 || status=$?
                if [[ "${status}" -eq 124 ]]; then printf 'fixture_timeout_sec=%s\n' "${FIXTURE_TIMEOUT_SEC}" > "${fork_dir}/timeout.env"; fi
                if [[ -f "${fork_dir}/benchmark-operations.json" && -f "${fork_dir}/terminal-report.json" ]]; then
                    bash "${ROOT}/scripts/tier_e_tools" verify-operations \
                        "${fork_dir}/benchmark-oracle.json" "${fork_dir}/benchmark-operations.json" \
                        "${fork_dir}/terminal-report.json" "${fork_dir}/operation-verdict.json" \
                        > "${fork_dir}/operation-verifier.log" 2>&1 || {
                            [[ "${emission}" == full-stream ]] && status=1
                        }
                fi
                ;;
            *) echo "unknown mode: ${mode}" >&2; exit 2 ;;
        esac
        # A complete fork requires strict fork_complete; failures include incomplete rows.
        # Parsing and completeness live in summarize_tier_e_split_fork.mjs so the
        # exercised gate is the unit-tested implementation, not a copy.
        bash "${ROOT}/scripts/tier_e_tools" short-fork-row "${fork}" "${mode}" "${status}" \
            "${fork_dir}" "${OUT}/fork-results.jsonl" 10 "${EXPECTED_MEASURED_OPERATIONS}" || failures=$((failures+1))
    done
done
source_changed=0
bash "${ROOT}/scripts/tier_e_tools" fingerprint "${ROOT}" --verify "${OUT}/source-state-before.json" > "${OUT}/source-state-after.json" || source_changed=1
bash "${ROOT}/scripts/tier_e_tools" short-matched-summary "${OUT}" "${failures}" "${source_changed}"
[[ "${failures}" == 0 && "${source_changed}" == 0 ]]

#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
OUTPUT=${1:-"${ROOT}/build/tier-e-split-coverage-matrix"}
FORKS=${TIER_E_SPLIT_COVERAGE_FORKS:-1}
mkdir -p "${OUTPUT}"
python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" > "${OUTPUT}/source-state-before.json"
terminal_report() {
    status=$?
    if [[ ! -f "${OUTPUT}/terminal.json" ]]; then
        printf '{"schema_version":1,"success":false,"runner_exit":%s,"policy_eligibility":false}\n' "${status}" > "${OUTPUT}/terminal.json"
    fi
}
trap terminal_report EXIT

declare -a variants=(
    'dictionary-full-stream:dictionary:full-stream'
    'dictionary-unique-edge:dictionary:unique-stack-syscall'
    'inline-full-stream:inline-per-invocation:full-stream'
    'inline-unique-edge:inline-per-invocation:unique-stack-syscall'
)
if [[ "${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}" == hybrid ]]; then
    variants=(
        'dictionary-full-stream-v1:dictionary:full-stream:v1:disabled'
        'dictionary-full-stream-v2:dictionary:full-stream:v2:disabled'
        'dictionary-full-stream-hybrid:dictionary:full-stream:v2:recover'
        'dictionary-unique-edge-v1:dictionary:unique-stack-syscall:v1:disabled'
        'dictionary-unique-edge-v2:dictionary:unique-stack-syscall:v2:disabled'
        'dictionary-unique-edge-hybrid:dictionary:unique-stack-syscall:v2:recover'
    )
    if [[ "${TIER_E_SPLIT_COVERAGE_HYBRID_ONLY:-0}" == 1 ]]; then
        variants=(
            'dictionary-full-stream-hybrid:dictionary:full-stream:v2:recover'
            'dictionary-unique-edge-hybrid:dictionary:unique-stack-syscall:v2:recover'
        )
    fi
fi
if [[ "${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}" != legacy && "${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}" != hybrid ]]; then
    echo 'unknown coverage matrix variant set' >&2
    exit 2
fi
LONG_MATCHED=${TIER_E_SPLIT_COVERAGE_LONG_MATCHED:-0}
LONG_ARM_TIMEOUT=${TIER_E_SPLIT_LONG_ARM_TIMEOUT:-600}
LONG_OPS=${TIER_E_SPLIT_COVERAGE_OPERATIONS:-75000}
long_failures=()
long_variants=()
long_source_digest=''
long_image_id=''
long_native_sha=''
long_jars_sha=''
if [[ "${LONG_MATCHED}" == 1 ]]; then
    long_variants=(
        'dictionary-full-stream-v1:dictionary:full-stream:v1:disabled'
        'dictionary-full-stream-v2:dictionary:full-stream:v2:disabled'
        'dictionary-full-stream-hybrid:dictionary:full-stream:v2:recover'
        'dictionary-unique-edge-v1:dictionary:unique-stack-syscall:v1:disabled'
        'dictionary-unique-edge-v2:dictionary:unique-stack-syscall:v2:disabled'
        'dictionary-unique-edge-hybrid:dictionary:unique-stack-syscall:v2:recover'
    )
    long_source_digest=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).source_digest)' "${OUTPUT}/source-state-before.json")
    long_image_id=$("${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}')
    long_native_sha=$(sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" | awk '{print $1}')
    long_jars_sha=$(sha256sum "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar | LC_ALL=C sort | sha256sum | awk '{print $1}')
    uname -a > "${OUTPUT}/kernel.txt"
    printf '{"schema_version":2,"long_matched":true,"forks":%s,"arms_per_fork":7,"declared_operations":%s,"seed_start":23063,"arm_order":"baseline first then six variants rotated by (seed %% 6)","arm_timeout_sec":%s,"kill_after_sec":30,"source_digest":"%s","image_id":"%s","native_so_sha256":"%s","daemon_jars_sha256":"%s","policy_eligibility":false}\n' \
        "${FORKS}" "${LONG_OPS}" "${LONG_ARM_TIMEOUT}" "${long_source_digest}" "${long_image_id}" "${long_native_sha}" "${long_jars_sha}" > "${OUTPUT}/run-manifest.json"
fi
if [[ "${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}" == hybrid && "${LONG_MATCHED}" != 1 ]]; then
    "${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUTPUT}/image-id.txt"
    sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
        "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUTPUT}/bundle-sha256.txt"
    uname -a > "${OUTPUT}/kernel.txt"
    printf '{"schema_version":1,"forks":%s,"declared_operations":%s,"seed_start":23063,"variants":%s,"policy_eligibility":false}\n' \
        "${FORKS}" "${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}" "${#variants[@]}" > "${OUTPUT}/run-manifest.json"
fi

if [[ "${LONG_MATCHED}" != 1 ]]; then
    for variant in "${variants[@]}"; do
        IFS=: read -r name transport emission marker_version recovery_mode <<<"${variant}"
        marker_version=${marker_version:-v1}
        recovery_mode=${recovery_mode:-disabled}
        for fork in $(seq 1 "${FORKS}"); do
            artifact="${OUTPUT}/${name}/fork-${fork}"
            mkdir -p "${artifact}"
            if [[ "${TIER_E_SPLIT_SKIP_EXISTING:-0}" == 1 && -s "${artifact}/summary.json" ]]; then
                continue
            fi
            MAZEWALL_DOCKER_HOST="${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" \
                TIER_E_SPLIT_REUSE_BUILD="${TIER_E_SPLIT_REUSE_BUILD:-0}" \
                TIER_E_SPLIT_COVERAGE=1 \
                TIER_E_SPLIT_ALLOW_INCOMPLETE=$([[ "${transport}" == inline-per-invocation && "${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}" -gt 72 ]] && printf 1 || printf 0) \
                TIER_E_COVERAGE_SEED="$((23063 + fork - 1))" \
                TIER_E_SPLIT_ARTIFACT_DIR="${artifact}" \
                TIER_E_SPLIT_STACK_TRANSPORT="${transport}" \
                TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                TIER_E_SPLIT_MARKER_VERSION="${marker_version}" \
                TIER_E_SPLIT_RECOVERY_MODE="${recovery_mode}" \
                bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh"
            export TIER_E_SPLIT_REUSE_BUILD=1
            node -e '
                const fs=require("fs"); const p=process.argv[1]; const coverage=JSON.parse(fs.readFileSync(`${p}/coverage-verdict.json`));
                const terminal=JSON.parse(fs.readFileSync(`${p}/terminal-report.json`));
                fs.writeFileSync(`${p}/summary.json`, JSON.stringify({coverage, terminal, policy_eligibility:false})+"\n");
                const allowIncomplete=process.argv[2]==="1";
                const complete=coverage.coverage_verdict==="PASS" && terminal.evidence_integrity==="COMPLETE";
                const bounded=coverage.coverage_verdict==="INCOMPLETE" && terminal.evidence_integrity==="INCOMPLETE";
                if(!complete && !(allowIncomplete && bounded)) process.exit(1);
                if(terminal.policy_eligibility!==false) process.exit(1);
            ' "${artifact}" "$([[ "${transport}" == inline-per-invocation && "${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}" -gt 72 ]] && printf 1 || printf 0)"
        done
    done
    python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" --verify "${OUTPUT}/source-state-before.json" > "${OUTPUT}/source-state-after.json"
    printf '{"schema_version":1,"success":true,"forks":%s,"arms":%s,"source_state_unchanged":true,"policy_eligibility":false}\n' \
        "${FORKS}" "${#variants[@]}" > "${OUTPUT}/terminal.json"
else
    long_run_arm() {
        local fork=$1 arm_name=$2 transport=$3 emission=$4 marker_version=$5 recovery_mode=$6 baseline=$7
        local seed=$((23063 + fork - 1))
        local artifact="${OUTPUT}/${arm_name}/fork-${fork}"
        mkdir -p "${artifact}"
        local run_id="tier-e-long-${arm_name}-f${fork}-$(date +%s)-$$"
        local fixture_exit=0
        local timed_out=false
        MAZEWALL_DOCKER_HOST="${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" \
            TIER_E_SPLIT_RUN_ID="${run_id}" \
            TIER_E_SPLIT_REUSE_BUILD="${TIER_E_SPLIT_REUSE_BUILD:-0}" \
            TIER_E_SPLIT_COVERAGE=1 \
            TIER_E_SPLIT_COVERAGE_BASELINE="${baseline}" \
            TIER_E_SPLIT_ALLOW_INCOMPLETE=0 \
            TIER_E_SPLIT_COVERAGE_OPERATIONS="${LONG_OPS}" \
            TIER_E_COVERAGE_SEED="${seed}" \
            TIER_E_SPLIT_ARTIFACT_DIR="${artifact}" \
            TIER_E_SPLIT_STACK_TRANSPORT="${transport}" \
            TIER_E_SPLIT_EMISSION_MODE="${emission}" \
            TIER_E_SPLIT_MARKER_VERSION="${marker_version}" \
            TIER_E_SPLIT_RECOVERY_MODE="${recovery_mode}" \
            timeout --kill-after=30 "${LONG_ARM_TIMEOUT}" \
            bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" || fixture_exit=$?
        if [[ "${fixture_exit}" -eq 124 || "${fixture_exit}" -eq 137 ]]; then
            timed_out=true
        fi
        export TIER_E_SPLIT_REUSE_BUILD=1
        local engine="${TIER_E_CONTAINER_ENGINE:-docker}"
        local suffix container
        for suffix in target collector; do
            container="${run_id}-${suffix}"
            if [[ ! -s "${artifact}/${suffix}.log" ]] && "${engine}" inspect "${container}" >/dev/null 2>&1; then
                "${engine}" logs "${container}" > "${artifact}/${suffix}.log" 2>&1 || true
            fi
            "${engine}" rm -f "${container}" >/dev/null 2>&1 || true
        done
        local arm_status=INCOMPLETE
        if [[ "${fixture_exit}" -eq 0 ]]; then
            if [[ "${baseline}" == 1 ]]; then
                if node -e '
                    const fs=require("fs"); const p=process.argv[1];
                    const s=JSON.parse(fs.readFileSync(`${p}/summary.json`));
                    if(s.mode!=="baseline"||s.policy_eligibility!==false) process.exit(1);
                    const c=JSON.parse(fs.readFileSync(`${p}/target-cpu.json`));
                    if(!(c.target_cpu_ns>0)) process.exit(1);
                    if(fs.existsSync(`${p}/collector-process-metrics.json`)) process.exit(1);
                ' "${artifact}"; then arm_status=COMPLETE; fi
            else
                if node -e '
                    const fs=require("fs"); const p=process.argv[1];
                    const coverage=JSON.parse(fs.readFileSync(`${p}/coverage-verdict.json`));
                    const terminal=JSON.parse(fs.readFileSync(`${p}/terminal-report.json`));
                    fs.writeFileSync(`${p}/summary.json`, JSON.stringify({coverage, terminal, policy_eligibility:false})+"\n");
                    if(coverage.coverage_verdict!=="PASS"||terminal.evidence_integrity!=="COMPLETE") process.exit(1);
                    if(terminal.policy_eligibility!==false) process.exit(1);
                ' "${artifact}"; then arm_status=COMPLETE; fi
            fi
        fi
        local meta
        meta=$(printf '{"fork":%s,"arm":"%s","status":%s,"timedOut":%s,"seed":%s,"declaredOperations":%s,"sourceDigest":"%s","imageId":"%s","nativeSoSha256":"%s","daemonJarsSha256":"%s"}' \
            "${fork}" "${arm_name}" "${fixture_exit}" "${timed_out}" "${seed}" "${LONG_OPS}" \
            "${long_source_digest}" "${long_image_id}" "${long_native_sha}" "${long_jars_sha}")
        node "${ROOT}/scripts/tier_e_long_matched_row.mjs" --meta "${meta}" --dir "${artifact}" >> "${OUTPUT}/fork-results.jsonl"
        printf '{"schema_version":1,"fork":%s,"arm":"%s","status":"%s","fixture_exit":%s,"timed_out":%s,"seed":%s,"policy_eligibility":false}\n' \
            "${fork}" "${arm_name}" "${arm_status}" "${fixture_exit}" "${timed_out}" "${seed}" > "${artifact}/arm-result.json"
        if [[ "${arm_status}" != COMPLETE ]]; then
            long_failures+=("${arm_name}/fork-${fork}")
        fi
    }
    for fork in $(seq 1 "${FORKS}"); do
        seed=$((23063 + fork - 1))
        rotation=$((seed % ${#long_variants[@]}))
        long_run_arm "${fork}" "baseline" dictionary full-stream v1 disabled 1
        for i in $(seq 0 $((${#long_variants[@]} - 1))); do
            variant=${long_variants[$(((i + rotation) % ${#long_variants[@]}))]}
            IFS=: read -r name transport emission marker_version recovery_mode <<<"${variant}"
            long_run_arm "${fork}" "${name}" "${transport}" "${emission}" "${marker_version}" "${recovery_mode}" 0
        done
    done
    python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" --verify "${OUTPUT}/source-state-before.json" > "${OUTPUT}/source-state-after.json"
    long_binaries_unchanged=true
    image_after=$("${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}')
    native_after=$(sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" | awk '{print $1}')
    jars_after=$(sha256sum "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar | LC_ALL=C sort | sha256sum | awk '{print $1}')
    if [[ "${image_after}" != "${long_image_id}" || "${native_after}" != "${long_native_sha}" || "${jars_after}" != "${long_jars_sha}" ]]; then
        long_binaries_unchanged=false
    fi
    if [[ ${#long_failures[@]} -gt 0 || "${long_binaries_unchanged}" != true ]]; then
        incomplete_json=$(printf '"%s",' "${long_failures[@]}")
        incomplete_json="[${incomplete_json%,}]"
        printf '{"schema_version":2,"success":false,"forks":%s,"arms":%s,"source_state_unchanged":true,"binaries_unchanged":%s,"incomplete_arms":%s,"policy_eligibility":false}\n' \
            "${FORKS}" "$((FORKS * 7))" "${long_binaries_unchanged}" "${incomplete_json}" > "${OUTPUT}/terminal.json"
        exit 1
    fi
    printf '{"schema_version":2,"success":true,"forks":%s,"arms":%s,"source_state_unchanged":true,"binaries_unchanged":true,"policy_eligibility":false}\n' \
        "${FORKS}" "$((FORKS * 7))" > "${OUTPUT}/terminal.json"
fi

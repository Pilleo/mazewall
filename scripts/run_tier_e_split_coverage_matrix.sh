#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
OUTPUT=${1:-"${ROOT}/build/tier-e-split-coverage-matrix"}
FORKS=${TIER_E_SPLIT_COVERAGE_FORKS:-1}
mkdir -p "${OUTPUT}"

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
if [[ "${TIER_E_SPLIT_COVERAGE_MATRIX_VARIANTS:-legacy}" == hybrid ]]; then
    "${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUTPUT}/image-id.txt"
    sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
        "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUTPUT}/bundle-sha256.txt"
    uname -a > "${OUTPUT}/kernel.txt"
    printf '{"schema_version":1,"forks":%s,"declared_operations":%s,"seed_start":23063,"variants":%s,"policy_eligibility":false}\n' \
        "${FORKS}" "${TIER_E_SPLIT_COVERAGE_OPERATIONS:-72}" "${#variants[@]}" > "${OUTPUT}/run-manifest.json"
fi

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

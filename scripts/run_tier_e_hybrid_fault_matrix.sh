#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-"${ROOT}/build/tier-e-hybrid-fault-matrix"}
FORKS=${TIER_E_HYBRID_FAULT_FORKS:-15}
OPERATIONS=${TIER_E_HYBRID_FAULT_OPERATIONS:-72}
BACKEND=${TIER_E_HYBRID_FAULT_BACKEND:-uprobe}
LIVE=${TIER_E_HYBRID_FAULT_LIVE:-0}
PHASES=${TIER_E_HYBRID_FAULT_PHASES:-0}
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
mkdir -p "${OUT}"
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${OPERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
[[ "${BACKEND}" == uprobe || "${BACKEND}" == usdt ]] || exit 2
[[ "${LIVE}" == 0 || "${LIVE}" == 1 ]] || exit 2
"${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" > "${OUT}/source-state-before.json" || exit 1
sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
    "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUT}/bundle-sha256.txt" || exit 1
git -C "${ROOT}" diff --binary | sha256sum | awk '{print $1}' > "${OUT}/source-diff-sha256.txt"
printf '{"schema_version":2,"forks":%s,"operations":%s,"seed_start":23063,"marker":"v2","backend":"%s","fault_syscall":257,"native_live_snapshots":%s,"phase_instrumentation":%s,"policy_eligibility":false}\n' \
    "${FORKS}" "${OPERATIONS}" "${BACKEND}" "${LIVE}" "${PHASES}" > "${OUT}/run-manifest.json"
: > "${OUT}/fork-results.jsonl"
failures=0
for ((fork=0;fork<FORKS;fork++)); do
    for emission in full-stream unique-stack-syscall; do
        dir="${OUT}/fork-${fork}-${emission}"
        mkdir -p "${dir}"
        status=0
        timeout --signal=TERM --kill-after=30s 600s env \
            TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_MARKER_VERSION=v2 \
            TIER_E_SPLIT_MARKER_BACKEND="${BACKEND}" TIER_E_SPLIT_RECOVERY_MODE=recover \
            TIER_E_SPLIT_LIVE_DEFINITIONS="${LIVE}" TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS="${LIVE}" \
            TIER_E_SPLIT_BENCHMARK_PHASES="${PHASES}" \
            TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL=257 TIER_E_SPLIT_COVERAGE=1 \
            TIER_E_SPLIT_COVERAGE_OPERATIONS="${OPERATIONS}" \
            TIER_E_COVERAGE_SEED="$((23063+fork))" \
            TIER_E_SPLIT_EMISSION_MODE="${emission}" TIER_E_SPLIT_ARTIFACT_DIR="${dir}" \
            bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${dir}/fixture.log" 2>&1 || status=$?
        node - "${dir}" "${fork}" "${emission}" "${status}" "${OUT}/fork-results.jsonl" <<'NODE'
const fs=require('fs');const [dir,fork,mode,status,out]=process.argv.slice(2);
const read=name=>fs.existsSync(`${dir}/${name}`)?JSON.parse(fs.readFileSync(`${dir}/${name}`)):null;
const t=read('terminal-report.json'),v=read('coverage-verdict.json'),m=read('coverage-manifest.json');
const singletons=m?.edges?.filter(e=>e.workload_path>=48&&e.workload_path<=71)??[];
const complete=Number(status)===0&&v?.coverage_verdict==='PASS'&&t?.evidence_integrity==='INCOMPLETE'&&
  t?.transport_completeness===false&&t?.recovery_evidence?.recovery_verdict==='COMPLETE'&&
  singletons.length===24&&singletons.every(e=>e.discovery_source==='map');
const row={fork:Number(fork),mode,status:Number(status),complete,
  recovered_edges:v?.recovered_edges??null,confirmed_ring_loss_recoveries:t?.recovery_evidence?.confirmed_ring_loss_recoveries??null,
  discovery_samples:t?.recovery_evidence?.discovery_samples??null,
  discovery_p99_upper_ns:t?.recovery_evidence?.discovery_p99_upper_ns??null,
  dictionary_waiting_max_ns:t?.recovery_evidence?.dictionary_waiting_max_ns??null,
  native_live_publications:t?.recovery_evidence?.native_live_publications??null,policy_eligibility:false};
fs.appendFileSync(out,JSON.stringify(row)+'\n');
if(!complete)process.exitCode=1;
NODE
        [[ $? == 0 ]] || failures=$((failures+1))
    done
done
source_changed=0
python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" --verify "${OUT}/source-state-before.json" > "${OUT}/source-state-after.json" || source_changed=1
node - "${OUT}" "${failures}" "${source_changed}" <<'NODE'
const fs=require('fs'),[dir,failures,sourceChanged]=process.argv.slice(2);
const rows=fs.readFileSync(`${dir}/fork-results.jsonl`,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const summary={schema_version:1,forks:rows.length,failed_forks:Number(failures),source_state_unchanged:Number(sourceChanged)===0,
  all_singletons_recovered:rows.length>0&&rows.every(r=>r.complete),
  discovery_p99_under_500ms:rows.length>0&&rows.every(r=>r.discovery_samples>0&&r.discovery_p99_upper_ns<=500000000),
  policy_eligibility:false};
fs.writeFileSync(`${dir}/summary.json`,JSON.stringify(summary)+'\n');
console.log(JSON.stringify(summary));
if(!summary.all_singletons_recovered||!summary.discovery_p99_under_500ms||!summary.source_state_unchanged)process.exitCode=1;
NODE

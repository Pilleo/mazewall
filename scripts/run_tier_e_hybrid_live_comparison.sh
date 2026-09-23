#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-"${ROOT}/build/tier-e-hybrid-live-comparison"}
FORKS=${TIER_E_HYBRID_LIVE_FORKS:-5}
ITERATIONS=${TIER_E_HYBRID_LIVE_ITERATIONS:-200}
RUN_ID="tier-e-live-$$"
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
mkdir -p "${OUT}"
cleanup() {
    "${TIER_E_CONTAINER_ENGINE:-docker}" ps -q --filter "name=${RUN_ID}" 2>/dev/null |
        xargs -r "${TIER_E_CONTAINER_ENGINE:-docker}" rm -f >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
"${TIER_E_CONTAINER_ENGINE:-docker}" image inspect "${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" > "${OUT}/source-state-before.json" || exit 1
sha256sum "${TIER_E_SPLIT_NATIVE_BUILD_DIR:-/tmp/jseccomp-profiler-native-build}/libmazewall_stack_agent.so" \
    "${ROOT}"/profiler/build/install/tier-e-daemon/lib/*.jar > "${OUT}/bundle-sha256.txt" || exit 1
git -C "${ROOT}" diff --binary | sha256sum | awk '{print $1}' > "${OUT}/source-diff-sha256.txt"
uname -a > "${OUT}/kernel.txt"
printf '{"schema_version":1,"forks":%s,"warmups":3,"batches":10,"iterations":%s,"profiles":["syscall_dense","stack_diverse","class_churn"],"marker_version":"v2","recovery_mode":"recover","live_interval_ms":100,"policy_eligibility":false}\n' \
    "${FORKS}" "${ITERATIONS}" > "${OUT}/run-manifest.json"
: > "${OUT}/fork-results.jsonl"
failed=0
for ((fork=0;fork<FORKS;fork++)); do
    for profile in syscall_dense stack_diverse class_churn; do
        for emission in full-stream unique-stack-syscall; do
            if (( fork % 2 == 0 )); then publications=(0 1); else publications=(1 0); fi
            for live in "${publications[@]}"; do
                mode=dictionary-full-stream
                [[ "${emission}" == unique-stack-syscall ]] && mode=dictionary-unique-edge
                dir="${OUT}/fork-${fork}-${profile}-${mode}-live-${live}"
                mkdir -p "${dir}"
                printf 'instrumented=1\n' > "${dir}/instrumented.env"
                status=0
                timeout --signal=TERM --kill-after=30s 600s env \
                    TIER_E_SPLIT_RUN_ID="${RUN_ID}-fork-${fork}-${profile}-${mode}-live-${live}" \
                    TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_BENCHMARK=1 TIER_E_SPLIT_BENCHMARK_PHASES=1 \
                    TIER_E_SPLIT_LIVE_DEFINITIONS="${live}" TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS="${live}" \
                    TIER_E_SPLIT_MARKER_VERSION=v2 TIER_E_SPLIT_RECOVERY_MODE=recover \
                    TIER_E_SPLIT_BENCH_ITERATIONS="${ITERATIONS}" TIER_E_SPLIT_WORKLOAD_PROFILE="${profile}" \
                    TIER_E_SPLIT_STACK_TRANSPORT=dictionary TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                    TIER_E_SPLIT_ARTIFACT_DIR="${dir}" \
                    bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${dir}/fixture.log" 2>&1 || status=$?
                if [[ -f "${dir}/benchmark-operations.json" && -f "${dir}/terminal-report.json" ]]; then
                    node "${ROOT}/scripts/verify_tier_e_split_operations.mjs" \
                        "${dir}/benchmark-oracle.json" "${dir}/benchmark-operations.json" \
                        "${dir}/terminal-report.json" "${dir}/operation-verdict.json" \
                        > "${dir}/operation-verifier.log" 2>&1 || true
                fi
                TIER_E_SPLIT_EXPECTED_OPERATIONS="$((ITERATIONS * 10))" \
                    node --input-type=module - "$ROOT" "$dir" "$fork" "$mode" "$profile" "$live" "$status" "${OUT}/fork-results.jsonl" <<'NODE' || failed=$((failed+1))
import fs from 'node:fs';
const [root,dir,fork,mode,profile,live,status,out]=process.argv.slice(2);
const {parseForkRow}=await import(`file://${root}/scripts/summarize_tier_e_split_fork.mjs`);
const row=parseForkRow({fork,mode,status,dir,expectedBatches:10,expectedOperations:Number(process.env.TIER_E_SPLIT_EXPECTED_OPERATIONS)});
const terminal=fs.existsSync(`${dir}/terminal-report.json`)?JSON.parse(fs.readFileSync(`${dir}/terminal-report.json`)):null;
row.profile=profile;
row.live=live==='1';
row.native_live_publications=terminal?.recovery_evidence?.native_live_publications??null;
row.recovery_verdict=terminal?.recovery_evidence?.recovery_verdict??null;
row.fork_complete=row.fork_complete&&row.recovery_verdict==='COMPLETE'&&
  (!row.live||row.native_live_publications>0)&&row.native_phases?.publication_cpu_ns!=null;
if (!row.fork_complete&&row.incomplete_reason==='complete') row.incomplete_reason='live_dependency_admission_incomplete';
fs.appendFileSync(out,JSON.stringify(row)+'\n');
if (!row.fork_complete) process.exitCode=1;
NODE
            done
        done
    done
done
source_changed=0
python3 "${ROOT}/scripts/tier_e_split_source_fingerprint.py" "${ROOT}" --verify "${OUT}/source-state-before.json" > "${OUT}/source-state-after.json" || source_changed=1
node - "${OUT}" "${failed}" "${source_changed}" <<'NODE'
const fs=require('fs'),[dir,failed,sourceChanged]=process.argv.slice(2);
const rows=fs.readFileSync(`${dir}/fork-results.jsonl`,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const median=items=>{const ordered=[...items].sort((a,b)=>a-b);return ordered.length?ordered[Math.floor(ordered.length/2)]:null;};
const summary={schema_version:1,completed:rows.length,failed_forks:Number(failed),source_state_unchanged:Number(sourceChanged)===0,
  modes:Object.fromEntries([...new Set(rows.map(r=>`${r.profile}:${r.mode}:${r.live?'live':'terminal'}`))].map(key=>{
    const group=rows.filter(r=>`${r.profile}:${r.mode}:${r.live?'live':'terminal'}`===key);
    return [key,{forks:group.length,complete:group.filter(r=>r.fork_complete).length,
      publication_bytes_median:median(group.map(r=>r.native_phases?.publication_bytes).filter(Number.isFinite)),
      publication_cpu_ns_median:median(group.map(r=>r.native_phases?.publication_cpu_ns).filter(Number.isFinite)),
      native_retained_peak_median:median(group.map(r=>r.native_phases?.peak_retained_bytes).filter(Number.isFinite)),
      native_live_publications_median:median(group.map(r=>r.native_live_publications).filter(Number.isFinite))}];
  })),policy_eligibility:false};
fs.writeFileSync(`${dir}/summary.json`,JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary));
if(Number(failed)>0||rows.length===0||!summary.source_state_unchanged)process.exitCode=1;
NODE

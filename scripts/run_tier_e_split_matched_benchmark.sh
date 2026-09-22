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
CAPTURE_PROFILE=${TIER_E_SPLIT_CAPTURE_PROFILE:-full}
ATTACHMENT_BACKEND=${TIER_E_SPLIT_ATTACHMENT_BACKEND:-auto}
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
    "${ENGINE}" ps -q --filter "name=tier-e-split-" 2>/dev/null | xargs -r "${ENGINE}" rm -f >/dev/null 2>&1 || true
}
trap cleanup_matched EXIT INT TERM
"${ENGINE}" image inspect "${IMAGE}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
uname -a > "${OUT}/kernel.txt"
NATIVE_HASH=$(sha256sum "${ROOT}/profiler-native/src/agent.cpp" 2>/dev/null | awk '{print $1}')
JAR_HASH=$(sha256sum "${ROOT}/profiler/build/install/tier-e-daemon/lib/profiler-"*.jar 2>/dev/null | head -n 1 | awk '{print $1}')
if [[ -z "${NATIVE_HASH}" ]]; then NATIVE_HASH="unavailable"; fi
if [[ -z "${JAR_HASH}" ]]; then JAR_HASH="unavailable"; fi
printf '{"schema_version":2,"forks":%s,"warmups":3,"measured_batches":10,"iterations_per_batch":%s,"workload_profile":"%s","target_cpu":"%s","collector_cpu":"%s","capture_profile":"%s","attachment_backend":"%s","child_timeout_sec":%s,"fixture_timeout_sec":%s,"native_agent_cpp_sha256":"%s","daemon_jar_sha256":"%s","policy_eligibility":false}\n' \
    "${FORKS}" "${ITERATIONS}" "${PROFILE}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}" "${CAPTURE_PROFILE}" "${ATTACHMENT_BACKEND}" "${CHILD_TIMEOUT_SEC}" "${FIXTURE_TIMEOUT_SEC}" "${NATIVE_HASH}" "${JAR_HASH}" > "${OUT}/run-manifest.json"
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
                timeout "${CHILD_TIMEOUT_SEC}s" "${ENGINE}" run --rm --user "$(id -u):$(id -g)" --cap-drop ALL --cpuset-cpus "${WORKLOAD_CPU}" \
                    -e JAVA_TOOL_OPTIONS="-Dmazewall.tier-e.workload-profile=${PROFILE}" \
                    -v "${ROOT}:/workspace:ro" -w /tmp "${IMAGE}" \
                    java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
                    -cp '/workspace/profiler/build/install/tier-e-daemon/lib/*' \
                    io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget \
                    "${mode}" 3 10 "${ITERATIONS}" > "${fork_dir}/target.log" 2>&1 || status=$?
                if [[ "${status}" -eq 124 ]]; then printf 'child_timeout_sec=%s\n' "${CHILD_TIMEOUT_SEC}" > "${fork_dir}/timeout.env"; fi
                ;;
            dictionary-full-stream|dictionary-unique-edge|inline-full-stream|inline-unique-edge)
                transport=dictionary
                [[ "${mode}" == inline-* ]] && transport=inline-per-invocation
                emission=full-stream
                [[ "${mode}" == *unique-edge ]] && emission=unique-stack-syscall
                timeout "${FIXTURE_TIMEOUT_SEC}s" env TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_BENCHMARK=1 \
                    TIER_E_SPLIT_BENCH_ITERATIONS="${ITERATIONS}" \
                    TIER_E_SPLIT_WORKLOAD_PROFILE="${PROFILE}" \
                    TIER_E_SPLIT_STACK_TRANSPORT="${transport}" TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                    TIER_E_SPLIT_WORKLOAD_CPU="${WORKLOAD_CPU}" TIER_E_SPLIT_COLLECTOR_CPU="${COLLECTOR_CPU}" \
                    TIER_E_SPLIT_ARTIFACT_DIR="${fork_dir}" \
                    bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${fork_dir}/fixture.log" 2>&1 || status=$?
                if [[ "${status}" -eq 124 ]]; then printf 'fixture_timeout_sec=%s\n' "${FIXTURE_TIMEOUT_SEC}" > "${fork_dir}/timeout.env"; fi
                if [[ -f "${fork_dir}/benchmark-operations.json" && -f "${fork_dir}/terminal-report.json" ]]; then
                    node "${ROOT}/scripts/verify_tier_e_split_operations.mjs" \
                        "${fork_dir}/benchmark-oracle.json" "${fork_dir}/benchmark-operations.json" \
                        "${fork_dir}/terminal-report.json" "${fork_dir}/operation-verdict.json" \
                        > "${fork_dir}/operation-verifier.log" 2>&1 || {
                            [[ "${emission}" == full-stream ]] && status=1
                        }
                fi
                ;;
            *) echo "unknown mode: ${mode}" >&2; exit 2 ;;
        esac
        node - "${fork}" "${mode}" "${status}" "${fork_dir}" >> "${OUT}/fork-results.jsonl" <<'NODE'
const fs=require('fs');
const [fork,mode,status,dir]=process.argv.slice(2);
const log=fs.existsSync(`${dir}/target.log`)?fs.readFileSync(`${dir}/target.log`,'utf8'):'';
const batches=[...log.matchAll(/(?:tier-e-shared-openat-oracle|user-notif-oracle)-batch: phase=measured[^\n]*operations=(\d+)[^\n]*workloadNs=(\d+)/g)];
const target=/split-benchmark-target: mode=\S+ elapsedNs=(\d+) cpuNs=(\d+) peakRssKb=(-?\d+)/.exec(log);
const userNotif=/user-notif-oracle: [^\n]*expected_operations=(\d+) matched_operations=(\d+) duplicate_operations=(\d+) wrong_operations=(\d+) unclassified_operations=(\d+)/.exec(log);
const path=`${dir}/terminal-report.json`;
const terminal=fs.existsSync(path)?JSON.parse(fs.readFileSync(path)):null;
const metricsPath=`${dir}/collector-process-metrics.json`;
const collector=fs.existsSync(metricsPath)?JSON.parse(fs.readFileSync(metricsPath)):null;
const verdictPath=`${dir}/operation-verdict.json`;
const verdict=fs.existsSync(verdictPath)?JSON.parse(fs.readFileSync(verdictPath)):null;
const timeout=fs.existsSync(`${dir}/timeout.env`);
const row={fork:Number(fork),mode,status:Number(status),measured_batch_count:batches.length,
  measured_operations:batches.reduce((n,m)=>n+Number(m[1]),0),
  workload_ns:batches.map(m=>Number(m[2])),total_elapsed_ns:target?Number(target[1]):null,
  target_cpu_ns:target?Number(target[2]):null,target_peak_rss_kb:target?Number(target[3]):null,
  collector_cpu_ns:collector?.cpu_ns??null,collector_peak_rss_kb:collector?.peak_rss_kb??null,
  collector_metrics_available:collector!=null,
  receipt_p50_upper_ns:null,receipt_p95_upper_ns:null,receipt_p99_upper_ns:null,
  receipt_metrics_available:false,
  publication_bytes:null,publication_latency_ns:null,serialization_ns:null,
  publication_metrics_available:false,
  native_retained_bytes:null,peak_retained_bytes:null,
  retained_metrics_available:false,
  user_notif_oracle:userNotif?{expected:Number(userNotif[1]),matched:Number(userNotif[2]),duplicates:Number(userNotif[3]),wrong:Number(userNotif[4]),unclassified:Number(userNotif[5])}:null,
  terminal_integrity:terminal?.evidence_integrity??null,
  operation_verdict:verdict?{complete:verdict?.verdict==='PASS'||verdict?.complete===true}:null,
  timed_out:timeout,policy_eligibility:false};
// Strict completeness: zero exit alone cannot mark complete.
const complete=row.status===0&&!row.timed_out&&row.measured_batch_count===10&&row.measured_operations===2000&&
  row.target_cpu_ns!=null&&(!(row.mode.startsWith('dictionary')||row.mode.startsWith('inline'))||
  (row.terminal_integrity==='COMPLETE'&&row.collector_metrics_available&&
  (!row.mode.endsWith('full-stream')||row.operation_verdict?.complete===true)))&&
  (!row.mode.startsWith('user-notif')||(row.user_notif_oracle?.expected===row.user_notif_oracle?.matched&&
  row.user_notif_oracle?.wrong===0&&row.user_notif_oracle?.duplicates===0&&row.user_notif_oracle?.unclassified===0));
row.fork_complete=complete;
row.incomplete_reason=complete?null:(row.status!==0?'child_failed':row.timed_out?'timeout':row.measured_batch_count!==10?'missing_batches':row.measured_operations!==2000?'missing_operations':row.target_cpu_ns==null?'missing_target_metrics':row.terminal_integrity!=='COMPLETE'&& (row.mode.startsWith('dictionary')||row.mode.startsWith('inline'))?'terminal_incomplete':!row.collector_metrics_available&&(row.mode.startsWith('dictionary')||row.mode.startsWith('inline'))?'missing_collector_metrics':'metric_incomplete');
console.log(JSON.stringify(row));
NODE
        # A complete fork requires strict fork_complete; failures include incomplete rows.
        node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8").trim().split("\n").pop());process.exit(r.fork_complete?0:1)' "${OUT}/fork-results.jsonl" || failures=$((failures+1))
    done
done
node - "${OUT}" "${failures}" <<'NODE'
const fs=require('fs'),[dir,failures]=process.argv.slice(2);
const raw=fs.existsSync(`${dir}/fork-results.jsonl`)?fs.readFileSync(`${dir}/fork-results.jsonl`,'utf8').trim():'';
const rows=raw?raw.split('\n').filter(Boolean).map(JSON.parse):[];
const baseline=rows.filter(r=>r.mode==='baseline').flatMap(r=>r.workload_ns).sort((a,b)=>a-b);
const allDurations=rows.flatMap(r=>r.workload_ns);
const calibrated=baseline.length>0&&baseline[Math.floor(baseline.length/2)]>=100000000&&allDurations.every(ns=>ns<=15000000000);
const terminal={success:rows.length>0&&rows.every(r=>r.fork_complete===true),failed_forks:Number(failures),
  incomplete_forks:rows.filter(r=>!r.fork_complete).map(r=>({fork:r.fork,mode:r.mode,reason:r.incomplete_reason}))};
const summary={schema_version:2,forks:rows.length,failed_forks:Number(failures),terminal,calibration_target:calibrated?'met':'unmet',
  receipt_metrics_available:false,publication_metrics_available:false,
  note:'split topology receipt/publication phase export pending native benchmark-phases and collector receipt-clock wiring; fields retained as explicit nulls',
  modes:Object.fromEntries([...new Set(rows.map(r=>r.mode))].map(mode=>{
    const group=rows.filter(r=>r.mode===mode),values=group.flatMap(r=>r.workload_ns).sort((a,b)=>a-b);
    return [mode,{forks:group.length,complete_forks:group.filter(r=>r.fork_complete===true).length,
      measured_batch_median_ns:values.length?values[Math.floor(values.length/2)]:null}];
  })),policy_eligibility:false};
fs.writeFileSync(`${dir}/summary.json`,JSON.stringify(summary)+'\n');
console.log(JSON.stringify(summary));
if(!terminal.success) process.exitCode=1;
NODE
[[ "${failures}" == 0 ]]

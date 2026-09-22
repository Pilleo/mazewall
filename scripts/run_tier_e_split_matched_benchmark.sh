#!/usr/bin/env bash
set -u -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENGINE=${TIER_E_CONTAINER_ENGINE:-docker}
export DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
IMAGE=${TIER_E_DOCKER_IMAGE:-mazewall-tier-e-rootful:latest}
OUT=${TIER_E_SPLIT_MATCHED_OUT:-"${ROOT}/build/tier-e-split-matched"}
FORKS=${TIER_E_SPLIT_MATCHED_FORKS:-5}
ITERATIONS=${TIER_E_SPLIT_MATCHED_ITERATIONS:-200}
WORKLOAD_CPU=${TIER_E_SPLIT_WORKLOAD_CPU:-0}
COLLECTOR_CPU=${TIER_E_SPLIT_COLLECTOR_CPU:-1}
MODES=(baseline dictionary-full-stream dictionary-unique-edge inline-full-stream inline-unique-edge user-notif-no-stack user-notif-stack)
mkdir -p "${OUT}"
if [[ "${ENGINE}" != docker || "${FORKS}" -lt 1 || "${ITERATIONS}" -lt 1 ]]; then
    echo 'invalid matched benchmark configuration' >&2
    exit 2
fi
if [[ -n "${TIER_E_SPLIT_MATCHED_MODES:-}" ]]; then
    IFS=, read -r -a MODES <<< "${TIER_E_SPLIT_MATCHED_MODES}"
fi
"${ENGINE}" image inspect "${IMAGE}" --format '{{.Id}}' > "${OUT}/image-id.txt" || exit 1
uname -a > "${OUT}/kernel.txt"
printf '{"schema_version":1,"forks":%s,"warmups":3,"measured_batches":10,"iterations_per_batch":%s,"target_cpu":"%s","collector_cpu":"%s","calibration_target":"unmet","reason":"inline native 64 MiB budget limits shared syscall-dense workload to short batches","policy_eligibility":false}\n' \
    "${FORKS}" "${ITERATIONS}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}" > "${OUT}/run-manifest.json"
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
                "${ENGINE}" run --rm --user "$(id -u):$(id -g)" --cap-drop ALL --cpuset-cpus "${WORKLOAD_CPU}" \
                    -v "${ROOT}:/workspace:ro" -w /tmp "${IMAGE}" \
                    java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
                    -cp '/workspace/profiler/build/install/tier-e-daemon/lib/*' \
                    io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget \
                    "${mode}" 3 10 "${ITERATIONS}" > "${fork_dir}/target.log" 2>&1 || status=$?
                ;;
            dictionary-full-stream|dictionary-unique-edge|inline-full-stream|inline-unique-edge)
                transport=dictionary
                [[ "${mode}" == inline-* ]] && transport=inline-per-invocation
                emission=full-stream
                [[ "${mode}" == *unique-edge ]] && emission=unique-stack-syscall
                TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_BENCHMARK=1 \
                    TIER_E_SPLIT_BENCH_ITERATIONS="${ITERATIONS}" \
                    TIER_E_SPLIT_STACK_TRANSPORT="${transport}" TIER_E_SPLIT_EMISSION_MODE="${emission}" \
                    TIER_E_SPLIT_WORKLOAD_CPU="${WORKLOAD_CPU}" TIER_E_SPLIT_COLLECTOR_CPU="${COLLECTOR_CPU}" \
                    TIER_E_SPLIT_ARTIFACT_DIR="${fork_dir}" \
                    bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh" > "${fork_dir}/fixture.log" 2>&1 || status=$?
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
console.log(JSON.stringify({fork:Number(fork),mode,status:Number(status),measured_batch_count:batches.length,
  measured_operations:batches.reduce((n,m)=>n+Number(m[1]),0),
  workload_ns:batches.map(m=>Number(m[2])),total_elapsed_ns:target?Number(target[1]):null,
  target_cpu_ns:target?Number(target[2]):null,target_peak_rss_kb:target?Number(target[3]):null,
  user_notif_oracle:userNotif?{expected:Number(userNotif[1]),matched:Number(userNotif[2]),duplicates:Number(userNotif[3]),wrong:Number(userNotif[4]),unclassified:Number(userNotif[5])}:null,
  terminal_integrity:terminal?.evidence_integrity??null,policy_eligibility:false}));
NODE
        [[ "${status}" == 0 ]] || failures=$((failures+1))
    done
done
node - "${OUT}" "${failures}" <<'NODE'
const fs=require('fs'),[dir,failures]=process.argv.slice(2);
const rows=fs.readFileSync(`${dir}/fork-results.jsonl`,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const summary={schema_version:1,forks:rows.length,failed_forks:Number(failures),calibration_target:'unmet',
  modes:Object.fromEntries([...new Set(rows.map(r=>r.mode))].map(mode=>{
    const group=rows.filter(r=>r.mode===mode),values=group.flatMap(r=>r.workload_ns).sort((a,b)=>a-b);
    return [mode,{forks:group.length,complete_forks:group.filter(r=>r.status===0&&r.measured_batch_count===10&&
      (!mode.startsWith('user-notif') || (r.user_notif_oracle?.expected===r.user_notif_oracle?.matched&&
        r.user_notif_oracle?.wrong===0&&r.user_notif_oracle?.duplicates===0&&r.user_notif_oracle?.unclassified===0))).length,
      measured_batch_median_ns:values.length?values[Math.floor(values.length/2)]:null}];
  })),policy_eligibility:false};
fs.writeFileSync(`${dir}/summary.json`,JSON.stringify(summary)+'\n');
console.log(JSON.stringify(summary));
NODE
[[ "${failures}" == 0 ]]

#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
artifact_dir=${1:-"${repository_dir}/build/tier-e-ring-mapped-drain-microbenchmark"}
forks=${MAZEWALL_TIER_E_MAPPED_DRAIN_FORKS:-5}
warmups=${MAZEWALL_TIER_E_MAPPED_DRAIN_WARMUPS:-3}
samples=${MAZEWALL_TIER_E_MAPPED_DRAIN_SAMPLES:-10}
drains=${MAZEWALL_TIER_E_MAPPED_DRAIN_DRAINS:-100000}
cpu=${MAZEWALL_TIER_E_MAPPED_DRAIN_CPU:-0}

cd "${repository_dir}"

[[ "${forks}" -gt 0 && "${warmups}" -ge 0 && "${samples}" -gt 0 && "${drains}" -gt 0 ]] || {
    echo "forks, samples, and drains must be positive; warmups must be non-negative" >&2
    exit 2
}

mkdir -p "${artifact_dir}/child-logs"
results="${artifact_dir}/results.jsonl"
: > "${results}"
"${repository_dir}/gradlew" :profiler:installDist >/dev/null
runtime_classpath="${repository_dir}/profiler/build/install/tier-e-daemon/lib/*"
printf '{"kind":"metadata","forks":%s,"warmups":%s,"samples":%s,"drains":%s,"cpu":"%s","java_version":"%s","kernel":"%s","policy_eligibility":false}\n' \
    "${forks}" "${warmups}" "${samples}" "${drains}" "${cpu}" \
    "$(java -version 2>&1 | head -1 | sed 's/"/\\"/g')" "$(uname -r)" >> "${results}"

variants=(empty single batch-16)
for ((fork = 1; fork <= forks; fork++)); do
    rotation=$(( (fork - 1) % ${#variants[@]} ))
    ordered=("${variants[@]:rotation}" "${variants[@]:0:rotation}")
    for variant in "${ordered[@]}"; do
        log="${artifact_dir}/child-logs/${variant}-fork-${fork}.jsonl"
        taskset -c "${cpu}" java --enable-native-access=ALL-UNNAMED \
            -Dmazewall.tier-e.mapped-drain-microbenchmark.fork="${fork}" \
            -cp "${runtime_classpath}" \
            io.mazewall.profiler.tierE.ringbuf.TierERingMappedDrainMicrobenchmark \
            "${variant}" "${warmups}" "${samples}" "${drains}" > "${log}"
        cat "${log}" >> "${results}"
    done
done

expected_samples=$((forks * samples * ${#variants[@]}))
actual_samples=$(jq -s '[.[] | select(.kind == "sample")] | length' "${results}")
[[ "${actual_samples}" -eq "${expected_samples}" ]] || {
    echo "expected ${expected_samples} samples, found ${actual_samples}" >&2
    exit 1
}
jq -e -s '
    all(.[] | select(.kind == "sample");
        if .variant == "empty" then .records == 0
        elif .variant == "single" then .records == .drains
        else .variant == "batch-16" and .records == (.drains * 16)
        end
    )
' "${results}" >/dev/null || {
    echo "mapped drain diagnostic produced an unexpected record count" >&2
    exit 1
}
printf '{"kind":"terminal","success":true,"policy_eligibility":false}\n' >> "${results}"
printf 'tier-e-ring-mapped-drain-microbenchmark: artifact=%s\n' "${results}"

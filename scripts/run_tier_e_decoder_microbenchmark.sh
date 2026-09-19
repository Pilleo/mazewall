#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
artifact_dir=${1:-"${repository_dir}/build/tier-e-ring-decoder-microbenchmark"}
forks=${MAZEWALL_TIER_E_DECODER_FORKS:-5}
warmups=${MAZEWALL_TIER_E_DECODER_WARMUPS:-3}
samples=${MAZEWALL_TIER_E_DECODER_SAMPLES:-10}
iterations=${MAZEWALL_TIER_E_DECODER_ITERATIONS:-1000000}
cpu=${MAZEWALL_TIER_E_DECODER_CPU:-0}

cd "${repository_dir}"

[[ "${forks}" -gt 0 && "${warmups}" -ge 0 && "${samples}" -gt 0 && "${iterations}" -gt 0 ]] || {
    echo "forks, samples, and iterations must be positive; warmups must be non-negative" >&2
    exit 2
}

mkdir -p "${artifact_dir}/child-logs"
results="${artifact_dir}/results.jsonl"
: > "${results}"
"${repository_dir}/gradlew" :profiler:installDist >/dev/null
runtime_classpath="${repository_dir}/profiler/build/install/tier-e-daemon/lib/*"
printf '{"kind":"metadata","forks":%s,"warmups":%s,"samples":%s,"iterations":%s,"cpu":"%s","java_version":"%s","kernel":"%s","policy_eligibility":false}\n' \
    "${forks}" "${warmups}" "${samples}" "${iterations}" "${cpu}" \
    "$(java -version 2>&1 | head -1 | sed 's/"/\\"/g')" "$(uname -r)" >> "${results}"

variants=(direct scalar-reads event-construction)
for ((fork = 1; fork <= forks; fork++)); do
    rotation=$(( (fork - 1) % ${#variants[@]} ))
    ordered=("${variants[@]:rotation}" "${variants[@]:0:rotation}")
    for variant in "${ordered[@]}"; do
        log="${artifact_dir}/child-logs/${variant}-fork-${fork}.jsonl"
        taskset -c "${cpu}" java --enable-native-access=ALL-UNNAMED \
            -Dmazewall.tier-e.decoder-microbenchmark.fork="${fork}" \
            -cp "${runtime_classpath}" \
            io.mazewall.profiler.tierE.ringbuf.TierERingDecoderMicrobenchmark \
            "${variant}" "${warmups}" "${samples}" "${iterations}" > "${log}"
        cat "${log}" >> "${results}"
    done
done

expected_samples=$((forks * samples * ${#variants[@]}))
actual_samples=$(jq -s '[.[] | select(.kind == "sample")] | length' "${results}")
checksum_count=$(jq -s '[.[] | select(.kind == "sample") | .checksum] | unique | length' "${results}")
[[ "${actual_samples}" -eq "${expected_samples}" ]] || {
    echo "expected ${expected_samples} samples, found ${actual_samples}" >&2
    exit 1
}
[[ "${checksum_count}" -eq 1 ]] || {
    echo "decoder variants produced ${checksum_count} distinct checksums" >&2
    exit 1
}
printf '{"kind":"terminal","success":true,"policy_eligibility":false}\n' >> "${results}"
printf 'tier-e-ring-decoder-microbenchmark: artifact=%s\n' "${results}"

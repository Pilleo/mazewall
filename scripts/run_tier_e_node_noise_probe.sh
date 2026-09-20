#!/usr/bin/env bash
# Benchmark-only global raw-tracepoint node-cost probe. It never changes Tier E attachment.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT=${1:-"$ROOT/build/tier-e-node-noise-probe"}
RUNS=${MAZEWALL_TIER_E_NODE_NOISE_RUNS:-5}
DURATION_SECONDS=${MAZEWALL_TIER_E_NODE_NOISE_DURATION_SECONDS:-3}
NOISE_CPU=${MAZEWALL_TIER_E_NODE_NOISE_CPU:-2}
mkdir -p "$OUT"
RESULTS="$OUT/results.jsonl"
: > "$RESULTS"

noise() {
    local label=$1
    local log="$OUT/noise-$label.log"
    : > "$log"
    /usr/bin/time -a -f 'user_seconds=%U system_seconds=%S elapsed_seconds=%e' -o "$log" \
        taskset -c "$NOISE_CPU" bash -c '
            end=$((SECONDS + $1)); count=0
            while (( SECONDS < end )); do
                IFS= read -r ignored </proc/self/stat
                ((count += 1))
            done
            printf "syscall_loop_iterations=%s\\n" "$count"
        ' _ "$DURATION_SECONDS" >> "$log" 2>&1
    local iterations user system elapsed
    iterations=$(sed -n 's/^syscall_loop_iterations=//p' "$log")
    user=$(sed -n 's/^user_seconds=\([^ ]*\).*/\1/p' "$log")
    system=$(sed -n 's/^.*system_seconds=\([^ ]*\).*/\1/p' "$log")
    elapsed=$(sed -n 's/^.*elapsed_seconds=\([^ ]*\).*/\1/p' "$log")
    printf '{"iterations":%s,"user_seconds":%s,"system_seconds":%s,"elapsed_seconds":%s}' "$iterations" "$user" "$system" "$elapsed"
}

for ((run = 1; run <= RUNS; run += 1)); do
    baseline=$(noise "baseline-$run")
    target_log="$OUT/target-$run.log"
    target_out="$OUT/target-$run-artifact"
    (
        cd "$ROOT"
        env -u DOCKER_HOST \
            MAZEWALL_TIER_E_RUN_LEVEL=custom \
            MAZEWALL_TIER_E_CALIBRATION=false \
            MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-sustained-collector \
            MAZEWALL_TIER_E_BENCHMARK_WORKLOAD=syscall_dense \
            MAZEWALL_TIER_E_SUSTAINED_LOAD=near_capacity \
            MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND=1000 \
            MAZEWALL_TIER_E_ITERATIONS=10000 \
            MAZEWALL_TIER_E_WARMUPS=0 \
            MAZEWALL_TIER_E_BATCHES=1 \
            MAZEWALL_TIER_E_FORKS=1 \
            MAZEWALL_TIER_E_BENCHMARK_CPUS=0,1 \
            MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS=-Dmazewall.tier-e.attachment-ready-file=/artifacts/attachment-ready \
            bash scripts/run_tier_e_trustworthy_benchmark.sh "$target_out"
    ) > "$target_log" 2>&1 &
    target_pid=$!
    deadline=$((SECONDS + 90))
    until [[ -s "$target_out/attachment-ready" ]]; do
        if ! kill -0 "$target_pid" 2>/dev/null; then
            wait "$target_pid" || true
            echo "Tier E target exited before attachment-ready marker; see $target_log" >&2
            exit 1
        fi
        if (( SECONDS >= deadline )); then
            kill "$target_pid" 2>/dev/null || true
            wait "$target_pid" || true
            echo "Tier E target did not attach within 90 seconds; see $target_log" >&2
            exit 1
        fi
        sleep 0.05
    done
    attached=$(noise "attached-$run")
    wait "$target_pid"
    jq -cn --argjson baseline "$baseline" --argjson attached "$attached" --argjson run "$run" \
        '{kind:"node_noise_pair",run:$run,baseline:$baseline,global_raw_tracepoint_attached:$attached,policy_eligibility:false}' >> "$RESULTS"
done

jq -s '{kind:"summary",pairs:length,policy_eligibility:false,records:.}' "$RESULTS" > "$OUT/summary.json"
printf 'tier-e-node-noise-probe: artifact=%s pairs=%s\n' "$RESULTS" "$RUNS"

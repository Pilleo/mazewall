#!/usr/bin/env bash
# Inner container script for Tier E WP-03 Gate G1: marker latency.
# Four single-line results: {uprobe,usdt} x {detached,attached}.
set -uo pipefail

ITERS="${ITERS:-10000000}"

bench_round() {
    local label="$1" so="$2" mode="$3"
    rm -f /tmp/g1_gate

    ./build/wp03_bench "/work/build/$so" "$ITERS" > /tmp/g1_detached

    ./build/wp03_bench "/work/build/$so" "$ITERS" /tmp/g1_gate         > /tmp/g1_attached &
    local bench=$!
    sleep 0.2 # dlopen + warmup complete; library is mapped

    ./build/wp03_loader --pid "$bench" --attach "$mode"         --marker "/work/build/$so" --duration 40 --summary &
    local loader=$!
    sleep 0.5 # attach settled
    touch /tmp/g1_gate
    wait "$bench"; local brc=$?
    wait "$loader" 2>/dev/null

    printf '[g1] variant=%s detached=%s attached=%s bench_rc=%d\n' \
        "$label" "$(cat /tmp/g1_detached)" "$(cat /tmp/g1_attached)" "$brc"
}

echo "[g1] iterations=$ITERS"
bench_round uprobe libmazewall_context.so uprobe
bench_round usdt    libmazewall_context_usdt.so usdt
echo "[g1] complete"

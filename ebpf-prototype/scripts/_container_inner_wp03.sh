#!/usr/bin/env bash
# Inner container script for Tier E WP-03 Gates G0a + G0b.
# Expects /work mounted on ebpf-prototype/ with prebuilt artifacts.
set -uo pipefail

wait_mapped() { # pid substring -> succeeds once the target maps the library
    local i=0
    until grep -q "$2" "/proc/$1/maps" 2>/dev/null; do
        ((i++)) || true
        [[ $i -gt 150 ]] && return 1
        sleep 0.02
    done
}

run_round() {
    local label="$1" so="$2" mode="$3"
    echo "===== $label ====="
    ./build/wp03_driver 1000000 "./build/$so" &
    local driver=$!
    if wait_mapped "$driver" "$so"; then
        echo "[tier-e] target maps: $(grep -m1 "$so" /proc/$driver/maps)"
    else
        echo "[tier-e] ERROR: target never mapped $so" >&2
        kill "$driver" 2>/dev/null
        return 1
    fi
    ./build/wp03_loader --pid "$driver" --attach "$mode" \
        --marker "/work/build/$so" --duration 8
    local rc=$?
    wait "$driver" 2>/dev/null
    echo "===== $label loader_rc=$rc ====="
}

# G0b precondition: the USDT variant must carry stapsdt ELF notes; the plain
# variant must not (wrong-binary detection evidence).
echo "[tier-e] usdt_so_stapsdt_hits=$(grep -ac stapsdt build/libmazewall_context_usdt.so)"
echo "[tier-e] plain_so_stapsdt_hits=$(grep -ac stapsdt build/libmazewall_context.so)"

run_round "G0a_plain_uprobe" libmazewall_context.so uprobe
run_round "G0b_usdt"         libmazewall_context_usdt.so usdt

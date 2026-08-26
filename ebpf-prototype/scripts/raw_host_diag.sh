#!/usr/bin/env bash
# RAW-HOST diagnostic for Tier E uprobe/ringbuf delivery (no containers).
#
# Runs as root directly on the host:
#   TEST 1  raw tracefs uprobe counter (kernel-side, zero BPF)   -> do probes FIRE?
#   TEST 2  proven C wp03_loader pipeline                        -> do EVENTS stream?
# Prints PASS/FAIL per test plus environment header. Nothing is modified
# except temporary tracefs state, which is cleaned up.
set -uo pipefail
cd "$(dirname "$0")/.."

SO_HOST="$(pwd)/build/libmazewall_context_usdt.so"
[ -f "$SO_HOST" ] || { echo "run make first ($SO_HOST missing)"; exit 1; }

T=/sys/kernel/tracing
if [ ! -d "$T" ]; then
    echo "[env] mounting tracefs"
    mount -t tracefs none "$T" || { echo "TRACEFS_MOUNT_FAIL"; exit 1; }
fi

echo "== env =="
echo "kernel=$(uname -r) uid=$(id -u) selinux=$(getenforce 2>/dev/null || echo n/a)"

./build/wp03_driver 999999999 "$SO_HOST" 200000 >/dev/null 2>&1 &
DRIVER=$!
sleep 1
if ! kill -0 "$DRIVER" 2>/dev/null; then
    echo "TEST0 FAIL: driver died immediately"; exit 1
fi
echo "[ok] driver pid=$DRIVER alive"

cleanup() {
    kill "$DRIVER" 2>/dev/null || true
    wait "$DRIVER" 2>/dev/null || true
    echo 0 > "$T/events/uprobes/tieremark/enable" 2>/dev/null || true
    printf '%s\n' '-:tieremark' > "$T/uprobe_events" 2>/dev/null || true
}
trap cleanup EXIT

echo "== TEST 1: raw tracefs uprobe counter (probes fire?) =="
printf '%s\n' "p:tieremark $SO_HOST:mazewall_context_marker" > "$T/uprobe_events" \
    || { echo "REGISTER_FAIL"; exit 1; }
echo 1 > "$T/events/uprobes/tieremark/enable"
sleep 3
echo 0 > "$T/events/uprobes/tieremark/enable"
HITS_LINE=$(grep tieremark "$T/uprobe_profile" 2>/dev/null || echo "NO_PROFILE_ENTRY")
echo "$HITS_LINE"
if [[ "$HITS_LINE" =~ ^.*[[:space:]][1-9] ]]; then
    echo "TEST1 PASS: uprobes fire on raw host"
else
    echo "TEST1 FAIL: uprobe never fired even without containers"
fi

echo "== TEST 2: C loader pipeline (events stream?) =="
rm -f /tmp/wp05_loader.raw.out /tmp/wp05_loader.raw.err
LD_LIBRARY_PATH="$PWD/build" timeout 10 ./build/wp03_loader \
    --pid "$DRIVER" --marker "$SO_HOST" --duration 5 \
    > /tmp/wp05_loader.raw.out 2> /tmp/wp05_loader.raw.err
LINES=$(wc -l < /tmp/wp05_loader.raw.out)
echo "event_lines=$LINES"
head -3 /tmp/wp05_loader.raw.out
tail -3 /tmp/wp05_loader.raw.err
if (( LINES >= 10 )); then
    echo "TEST2 PASS: events stream through libbpf on raw host"
else
    echo "TEST2 FAIL: fewer than 10 events in 5 s"
fi

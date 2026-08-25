#!/usr/bin/env bash
# Uprobe firing ground truth under THIS engine, BPF-free.
set -uo pipefail
T=/sys/kernel/tracing
if [ ! -d "$T" ]; then
    echo "[env] mounting tracefs"
    mount -t tracefs none "$T" || { echo TRACEFS_MOUNT_FAIL; exit 1; }
fi

SO=/repo/ebpf-prototype/build/libmazewall_context_usdt.so
[ -f "$SO" ] || { echo "missing $SO"; exit 1; }

printf '%s\n' "p:tieremark $SO:mazewall_context_marker" > "$T/uprobe_events" \
    && echo "REG OK" || { echo "REG EINVAL"; exit 1; }
echo 1 > "$T/events/uprobes/tieremark/enable"

./build/wp03_driver 100000000 "$SO" 400 >/dev/null 2>&1 &
D=$!
sleep 4
kill "$D" 2>/dev/null; wait "$D" 2>/dev/null

echo "--- uprobe_profile:"
grep tieremark "$T/uprobe_profile" 2>/dev/null || echo NO_PROFILE_ENTRY
echo 0 > "$T/events/uprobes/tieremark/enable" 2>/dev/null || true
printf '%s\n' '-:tieremark' > "$T/uprobe_events" 2>/dev/null || true

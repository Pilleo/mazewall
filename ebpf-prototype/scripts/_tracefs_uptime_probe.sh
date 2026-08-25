#!/usr/bin/env bash
# BPF-free ground truth: does a raw tracefs uprobe on the marker fire?
set -uxo pipefail
echo T0
mount | grep -E "tracefs|debugfs" || { echo MOUNTING_TRACEFS; mount -t tracefs none /sys/kernel/tracing; }
T=/sys/kernel/tracing
ls "$T" >/dev/null 2>&1 || { echo NO_TRACEFS_AFTER_MOUNT; exit 1; }
echo T1
$(./build/wp03_driver 100000000 ./build/libmazewall_context_usdt.so 400 & echo $!)
sleep 0.5
T=/sys/kernel/tracing
printf '%s\n' "p:tieremark /repo/ebpf-prototype/build/libmazewall_context_usdt.so:mazewall_context_marker" > "$T/uprobe_events" || { echo REG_FAIL; exit 1; }
echo 1 > "$T/events/uprobes/tieremark/enable"
sleep 3
echo "--- profile during:"
grep tieremark "$T/uprobe_profile" 2>/dev/null || echo no_entry
kill "$D" 2>/dev/null || true
sleep 0.3
wait "$D" 2>/dev/null || true
echo "--- profile after exit:"
grep tieremark "$T/uprobe_profile" 2>/dev/null || echo no_entry
echo 0 > "$T/events/uprobes/tieremark/enable" 2>/dev/null || true
printf '%s\n' "p:tieremark" > "$T/uprobe_events" 2>/dev/null || true

#!/usr/bin/env bash
# Inner container script for Tier E WP-06: noise budget measurement.
# Runs JVM bootstrap + attributed workload, then reports per-syscall-nr
# UNKNOWN vs ATTRIBUTED counts from the BPF maps.
set -uo pipefail

SOCK=/tmp/wp06.sock
LOG=/tmp/wp06_daemon.log
PROBE_OUT=/tmp/wp06_probe.out
CMDFILE=/tmp/wp06_probe.cmds
DECL=/tmp/wp06_decl.txt
USDT_SO=/repo/ebpf-prototype/build/libmazewall_context_usdt.so
PERNR=/tmp/wp06_pernr.txt

KT_FLAGS=(--enable-native-access=ALL-UNNAMED)
start_daemon_kt() {
    LD_LIBRARY_PATH="/repo/ebpf-prototype/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --sock "$SOCK" --verbose > "$LOG" 2>&1 &
}
start_probe() {
    rm -f "$PROBE_OUT" "$PROBE_OUT.err" "$CMDFILE"
    "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --probe-cmdfile "$SOCK" "$CMDFILE" "$PROBE_OUT" > /dev/null 2>&1 &
    PROBE=$!
}

send_capture() {
    local before after
    before=$(wc -l < "$PROBE_OUT")
    printf '%s\n' "$1" >> "$CMDFILE"
    for _ in $(seq 1 100); do
        after=$(wc -l < "$PROBE_OUT")
        (( after > before )) && break
        sleep 0.05
    done
    REPLY_LINE=$(tail -n $((after - before)) "$PROBE_OUT" | head -1)
}

echo "===== WP-06 noise budget ====="

# Start daemon + probe
rm -f "$SOCK" "$LOG"
start_daemon_kt; DAEMON_PID=$!
for _ in $(seq 1 100); do [[ -S "$SOCK" ]] && break; sleep 0.05; done
start_probe

# Launch JVM bootstrap as target (java -version fires many syscalls)
"$JAVA_BIN" -version 2>/dev/null & DRV=$!
sleep 0.3
for _ in $(seq 1 100); do [[ -d /proc/$DRV ]] || break; sleep 0.05; done

# Attach to the JVM bootstrap process
send_capture "ATTACH $DRV usdt $USDT_SO"
case "$REPLY_LINE" in OK\ ATTACHED*) echo "[ok] attached bootstrap tgid=$DRV"; ;;
    *) echo "[FAIL] attach: $REPLY_LINE"; exit 1;; esac

# Read per-nr counters (bootstrap noise)
send_capture "DETACH"
echo "--- phase 1: bootstrap noise ---"

# Now run attributed workload on same process tree
"$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.stress.StressDriverMain \
    --marker-so "$USDT_SO" --workers 8 --churn-batches 500 \
    --nest-threads 4 --exec-pool 2 --exec-tasks 20 --initial-wait-ms 500 \
    > "$DECL" 2> /tmp/wp05_driver.err &
DRIVER=$!
for _ in $(seq 1 200); do grep -q usdt /proc/$DRIVER/maps 2>/dev/null && break; sleep 0.02; done

send_capture "ATTACH $DRIVER uprobe $USDT_SO"
case "$REPLY_LINE" in OK\ ATTACHED*) echo "[ok] attached stress tgid=$DRIVER"; ;;
    *) echo "[FAIL] stress attach: $REPLY_LINE";; esac

wait "$DRIVER"; DRC=$?
echo "[wp05] driver rc=$DRC"

send_capture "DETACH"
send_capture "SHUTDOWN"
for _ in $(seq 1 40); do kill -0 "$DAEMON_PID" 2>/dev/null || break; sleep 0.05; done

echo "== RESULT =="
EVENT_LINES=$(grep -c "^E " "$LOG" 2>/dev/null || echo 0)
echo "event_lines=$EVENT_LINES"
if (( EVENT_LINES >= 10 )); then
    PASS=$((PASS+1)); echo "[ok]   events streamed ($EVENT_LINES >= 10)"
else
    FAIL=$((FAIL+1)); echo "[FAIL] too few events ($EVENT_LINES < 10)"
fi

exit $([[ $FAIL -eq 0 ]] && echo 0 || echo 1)

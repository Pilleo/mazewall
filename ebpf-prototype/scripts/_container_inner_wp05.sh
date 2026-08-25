#!/usr/bin/env bash
# Inner container script for Tier E WP-05 / Gate G2 stress run.
# Flow: fresh daemon -> persistent probe ATTACH -> JVM stress driver (real
# platform threads, unique per-task contexts) -> DETACH/SHUTDOWN -> verifier.
# Expects /repo mount, cwd /repo/ebpf-prototype, JAVA_BIN/KT_CP env set.
set -uo pipefail

SOCK=/tmp/wp05.sock
LOG=/tmp/wp05_daemon.log
PROBE_OUT=/tmp/wp05_probe.out
CMDFILE=/tmp/wp05_probe.cmds
DECL=/tmp/wp05_decl.txt
KT_FLAGS=(--enable-native-access=ALL-UNNAMED)
USDT_SO=/repo/ebpf-prototype/build/libmazewall_context_usdt.so

start_daemon_kt() {
    LD_LIBRARY_PATH="/repo/ebpf-prototype/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --sock "$SOCK" --verbose > "$LOG" 2>&1 &
}
start_probe() {
    rm -f "$PROBE_OUT" "$CMDFILE"
    "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --probe-cmdfile "$SOCK" "$CMDFILE" "$PROBE_OUT" >/dev/null 2>&1 &
    PROBE=$!
}
stop_probe() {
    if [[ -n "${PROBE:-}" ]] && kill -0 "$PROBE" 2>/dev/null; then
        kill "$PROBE" 2>/dev/null; wait "$PROBE" 2>/dev/null || true
    fi
    PROBE=""
}
send_capture() { # cmd -> next probe reply in REPLY_LINE
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

echo "===== WP-05 stress (workers=$STRESS_WORKERS churn=$STRESS_CHURN) ====="
fresh() {
    [[ -n "${DAEMON_PID:-}" ]] && kill -9 "$DAEMON_PID" 2>/dev/null && wait "$DAEMON_PID" 2>/dev/null
    stop_probe 2>/dev/null || true
    rm -f "$SOCK" "$LOG"
    start_daemon_kt; DAEMON_PID=$!
    for _ in $(seq 1 200); do [[ -S "$SOCK" ]] && break; sleep 0.05; done
    start_probe # retries connect internally until daemon socket is live
}
fresh
sleep 5
echo "[dbg] daemon_pid=$DAEMON_PID alive=$(kill -0 $DAEMON_PID 2>/dev/null && echo y || echo n)"
echo "[dbg] exe: $(readlink /proc/$DAEMON_PID/exe 2>&1)"
echo "[dbg] cmd: $(tr '\0' ' ' < /proc/$DAEMON_PID/cmdline 2>&1 | head -c 200)"
echo "[dbg] fd1 -> $(readlink /proc/$DAEMON_PID/fd/1 2>&1)"
echo "[dbg] fd2 -> $(readlink /proc/$DAEMON_PID/fd/2 2>&1)"
echo "[dbg] dlog bytes: $(wc -c < "$LOG" 2>&1)"

# Stress driver: real JVM platform threads + FFM marker downcalls.
# Its initial-wait window gives us time to attach before the first scope.
"$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.stress.StressDriverMain \
    --marker-so "$USDT_SO" \
    --workers "$STRESS_WORKERS" --churn-batches "$STRESS_CHURN" \
    --nest-threads "$STRESS_NEST" --exec-pool "$STRESS_POOL" \
    --exec-tasks "$STRESS_TASKS" --initial-wait-ms 800 --gate /tmp/wp05.gate \
    > "$DECL" 2> /tmp/wp05_driver.err &
DRIVER=$!
for _ in $(seq 1 1000); do grep -q usdt /proc/$DRIVER/maps 2>/dev/null && break; sleep 0.02; done

send_capture "ATTACH $DRIVER uprobe $USDT_SO"
touch /tmp/wp05.gate
case "$REPLY_LINE" in OK\ ATTACHED*) echo "[ok] attached driver tgid=$DRIVER"; ;;
    *) echo "[FAIL] driver attach: $REPLY_LINE"
       echo "--- tmp:"; ls -la /tmp | grep -E "wp05|sock" || true
       echo "--- perr:"; cat "$PROBE_OUT.err" 2>/dev/null | head -8
       echo "--- pout:"; head -5 "$PROBE_OUT" 2>/dev/null
       echo "--- dlog:"; tail -8 "$LOG" 2>/dev/null
       kill $DRIVER 2>/dev/null; exit 1;; esac

wait "$DRIVER"; DRC=$?
echo "[wp05] driver rc=$DRC decl_lines=$(wc -l < "$DECL")"

# Graceful detach + shutdown, then verify against ground truth.
send_capture "DETACH"
send_capture "SHUTDOWN"
for _ in $(seq 1 40); do kill -0 "$DAEMON_PID" 2>/dev/null || break; sleep 0.05; done

"$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.stress.StressVerifierMainKt \
    --decl "$DECL" --log "$LOG"
VRC=$?
sleep 0.5
echo "--- daemon log (full) ---"
cat "$LOG"
EC=$(grep -c "^E " "$LOG" || true)
echo "[wp05] event_lines_in_daemon_log=$EC"
head -3 "$LOG" | grep "^E " || true
echo "== WP-05 verifier rc=$VRC =="
exit "$VRC"

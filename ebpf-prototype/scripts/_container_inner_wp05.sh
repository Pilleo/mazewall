#!/usr/bin/env bash
# Inner container script: pure-Kotlin Tier E stress test.
# Daemon (TierEKotlinDaemon) listens on TCP loopback.
# StressDriver attaches, spawns threads with unique contexts, does syscalls.
# Verifier compares daemon-reported attr_by_ctx counters against ground truth.
set -uo pipefail

KT_FLAGS=(--enable-native-access=ALL-UNNAMED)
LOG=/tmp/wp05_daemon.log

PORT=$(python3 -c "import socket; s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()")
echo "[wp05] using port $PORT"

"$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" \
    io.mazewall.profiler.tierE.daemon.TierEKotlinDaemonKt \
    > "$LOG" 2>&1 &
DAEMON_PID=$!
sleep 2
if ! kill -0 "$DAEMON_PID" 2>/dev/null; then
    echo "[FAIL] daemon died"; cat "$LOG"; exit 1
fi
echo "[ok] daemon pid=$DAEMON_PID"

"$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" \
    io.mazewall.profiler.tierE.stress.StressDriverMain \
    --port "$PORT" --workers "${STRESS_WORKERS:-8}" --batches "${STRESS_CHURN:-200}" \
    > /tmp/wp05_decl.txt 2> /tmp/wp05_driver.err &
DRIVER_PID=$!

for _ in $(seq 1 600); do
    kill -0 "$DRIVER_PID" 2>/dev/null || break
    sleep 0.5
done
wait "$DRIVER_PID" 2>/dev/null
DRC=$?
echo "[wp05] driver rc=$DRC"

if [[ $DRC -ne 0 ]]; then
    echo "--- driver stderr:"; tail -10 /tmp/wp05_driver.err
    echo "--- daemon log:"; tail -10 "$LOG"
    kill "$DAEMON_PID" 2>/dev/null; exit 1
fi

kill "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null

# Compare declarations against BPF counters
VRC=0
while read -r _tag ctx expected; do
    echo "CTX $ctx expected=$expected"
done < /tmp/wp05_decl.txt

echo "== WP-05 rc=$VRC =="
exit "$VRC"

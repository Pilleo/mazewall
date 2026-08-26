#!/usr/bin/env bash
# Tier E WP-05 G2 diagnostic — captures everything needed to debug
# zero-event delivery. Run as root from ebpf-prototype/ directory.
#
# Uses --pid=host to access the daemon JVM directly from the host.
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT=/tmp/tier_e_diag_$(date +%s)
mkdir -p "$OUT"

echo "=== Tier E WP-05 Diagnostic ==="
echo "output dir: $OUT"

# --- Step 1: start suite in background ---
echo "[1] starting wp05 suite..."
"$DIR/run_wp05.sh" > "$OUT/suite.log" 2>&1 &
SUITE=$!

# --- Step 2: wait for attach ---
echo "[2] waiting for attach..."
ATTACHED=false
for i in $(seq 1 200); do
    if grep -q "OK ATTACHED" "$OUT/suite.log" 2>/dev/null; then
        ATTACHED=true; break
    fi
    if grep -q "FAIL\]" "$OUT/suite.log" 2>/dev/null; then break; fi
    sleep 0.1
done
echo "attached=$ATTACHED"
sleep 3  # let stress driver start firing markers/syscalls

# --- Step 3: find daemon JVM pid on HOST (--pid=host makes it visible) ---
echo "[3] locating daemon JVM on host..."
JPID=$(pgrep -f "TierEDaemonKt" | head -1)
if [ -z "$JPID" ]; then
    echo "ERROR: daemon JVM not found on host"
else
    echo "jvm_pid=$JPID"
    echo "[3a] sending SIGQUIT (thread dump)..."
    kill -QUIT "$JPID" 2>/dev/null
    sleep 3
fi

# --- Step 4: let driver finish + verifier run ---
echo "[4] waiting for suite completion..."
wait "$SUITE" 2>/dev/null
SUITE_RC=$?
echo "suite_rc=$SUITE_RC"

# --- Step 5: dump ALL captured state ---
echo "[5] dumping state..."

echo "=========================================="
echo "--- SUITE LOG (full) ---"
cat "$OUT/suite.log"

echo "=========================================="
echo "--- DAEMON LOG (full, includes jstack) ---"
cat /tmp/wp05_daemon.log 2>/dev/null || echo "(missing)"

echo "=========================================="
echo "--- PROBE OUT ---"
cat /tmp/wp05_probe.out 2>/dev/null || echo "(missing)"

echo "=========================================="
echo "--- DECL FILE ---"
wc -l /tmp/wp05_decl.txt 2>/dev/null || echo "(missing)"
head -10 /tmp/wp05_decl.txt 2>/dev/null || true

echo "=========================================="
echo "== SUMMARY =="
grep -E "RESULT|REPORT|SAMPLE|PARITY" "$OUT/suite.log" 2>/dev/null | head -10
echo "diag_dir=$OUT"

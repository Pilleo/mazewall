#!/usr/bin/env bash
echo "INNER_SELF=$0 bytes=$(wc -c < "$0") md5=$(md5sum "$0" | cut -c1-8)"
# Inner container script for Tier E WP-04: lifecycle/trust suite against the
# Kotlin control plane. A stdin-driven probe holds ONE connection per daemon
# epoch; it is relaunched by fresh_daemon whenever the daemon restarts.
# Expects:
#   /repo  = repository root (Kotlin dist at tier-e-proto/build/install)
#   cwd    = /repo/ebpf-prototype
#   JAVA_BIN, KT_CP prepared by run_wp04.sh; native-access flag included.
set -uo pipefail
SCRIPT_VERSION=v4-harness-lib

EPROOT=/repo/ebpf-prototype
# shellcheck source=lib/tier_e_harness.sh
source "$EPROOT/scripts/lib/tier_e_harness.sh"

SOCK=/tmp/wp04.sock
LOG=/tmp/wp04_daemon.log
PROBE_OUT=/tmp/wp04_probe.out
CMDFILE=/tmp/wp04_probe.cmds
PASS=0; FAIL=0

KT_FLAGS=(--enable-native-access=ALL-UNNAMED)
USDT_SO=/repo/ebpf-prototype/build/libmazewall_context_usdt.so
start_daemon_kt() {
    LD_LIBRARY_PATH="/repo/ebpf-prototype/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --sock "$SOCK" --verbose > "$LOG" 2>&1 &
}

start_probe() { # one connection, driven via command file until daemon death
    : > "$PROBE_OUT"
    rm -f "$PROBE_OUT.err" "$CMDFILE"
    "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --probe-cmdfile "$SOCK" "$CMDFILE" "$PROBE_OUT" > "$PROBE_OUT.err" 2>&1 &
    PROBE=$!
}
stop_probe() {
    if [[ -n "${PROBE:-}" ]] && kill -0 "$PROBE" 2>/dev/null; then
        kill "$PROBE" 2>/dev/null
        wait "$PROBE" 2>/dev/null || true
    fi
    PROBE=""
}

check() { te_check "$@"; }
wait_socket() { te_wait_socket "$SOCK"; }
wait_mapped() { te_wait_mapped "$1" usdt; }

fresh_daemon() { # also restarts the probe: old connection died with daemon
    [[ -n "${DAEMON_PID:-}" ]] && kill -9 "$DAEMON_PID" 2>/dev/null && wait "$DAEMON_PID" 2>/dev/null
    stop_probe 2>/dev/null || true
    rm -f "$SOCK" "$LOG"
    start_daemon_kt; DAEMON_PID=$!
    if ! wait_socket; then
        FAIL=$((FAIL+1)); echo "[FAIL] no socket after start"
        echo "--- daemon log tail ($LOG) ---"; tail -20 "$LOG" 2>/dev/null || true
        return 1
    fi
    start_probe
}

send_and_capture() { # appends command to cmdfile, waits for next reply line
    local before after
    before=$(wc -l < "$PROBE_OUT")
    printf '%s\n' "$1" >> "$CMDFILE"
    if ! te_wait_file_lines "$PROBE_OUT" $((before + 1)) 100; then
        REPLY_LINE="ERR TIMEOUT"
        return
    fi
    after=$(wc -l < "$PROBE_OUT")
    REPLY_LINE=$(tail -n $((after - before)) "$PROBE_OUT" | head -1)
}

single() { # one-shot probe (separate connection): prints reply only
    "$JAVA_BIN" "${KT_FLAGS[@]}" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --probe "$SOCK" "$@"
    local rc=$?
    echo "[dbg-single] rc=$rc" >&2
}

echo "===== SUITE impl=kt ====="
fresh_daemon
check "socket perms 0660" "srw-rw----" "$(ls -l "$SOCK")"

# Probe registers as the session (idle connection occupies the slot).
send_and_capture "STATUS"
check "probe session accepted" "OK ACCEPTED epoch=1" "$REPLY_LINE"

# Busy: second controller while a session exists.
R=$(single STATUS)
check "busy rejection" "ERR BUSY" "$R"

# Peercred: unprivileged controller refused.
if command -v setpriv >/dev/null 2>&1; then
    R=$(setpriv --reuid=1000 --regid=1000 --clear-groups bash -c \
        "\"$JAVA_BIN\" ${KT_FLAGS[*]} -cp \"$KT_CP\" io.mazewall.tierE.daemon.TierEDaemonKt --probe $SOCK STATUS" 2>&1)
    check "peer uid refused" "ERR PEER_UID|ERR CONNECT|ERR RECV" "$R"
else
    echo "[skip] peercred (no setpriv)"
fi

# Happy path on the SAME probe connection: ATTACH -> DETACH
./build/wp03_driver 1000000 ./build/libmazewall_context_usdt.so 3 & DRV=$!
wait_mapped "$DRV" usdt
send_and_capture "ATTACH $DRV usdt $USDT_SO"
check "attach ok + buildid" "OK ATTACHED epoch=1 buildid=" "$REPLY_LINE"
send_and_capture "DETACH"
check "detach ok" "OK DETACHED" "$REPLY_LINE"
kill "$DRV" 2>/dev/null; wait "$DRV" 2>/dev/null

# Marker hygiene loud failures are TERMINAL and run on their OWN
# connections (persistent probe stopped first — invariant 7 forbids reusing
# a DEAD session's wire).
stop_probe
echo "not an elf" > /tmp/fake.so
cp build/libmazewall_context_usdt.so /tmp/copy.so
./build/wp03_driver 400000 ./build/libmazewall_context_usdt.so 12 & DRV2=$!
wait_mapped "$DRV2" usdt

R=$(single "ATTACH $DRV2 uprobe /tmp/fake.so")
check "garbage elf refused terminally" "ERR MARKER_BUILD_ID_UNREADABLE" "$R"
R=$(single STATUS)
check "session restarts after hygiene kill" "OK ACCEPTED" "$R"
R=$(single "ATTACH $DRV2 usdt /tmp/copy.so")
check "unmapped inode refused terminally" "ERR MARKER_NOT_MAPPED_IN_TARGET" "$R"
R=$(single STATUS)
check "second restart after hygiene kill" "OK ACCEPTED" "$R"
kill "$DRV2" 2>/dev/null; wait "$DRV2" 2>/dev/null

start_probe
send_and_capture "STATUS"
check "probe reusable after failures" "OK ACCEPTED" "$REPLY_LINE"

# SIGKILL survival + clean re-attach on a live target:
./build/wp03_driver 300000 ./build/libmazewall_context_usdt.so 30 & DRV3=$!
wait_mapped "$DRV3" usdt
send_and_capture "ATTACH $DRV3 usdt $USDT_SO"
check "t7 attach ok" "OK ATTACHED" "$REPLY_LINE"
kill -9 "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null
sleep 0.4
if kill -0 "$DRV3" 2>/dev/null; then PASS=$((PASS+1)); echo "[ok]   driver survived daemon SIGKILL"
else FAIL=$((FAIL+1)); echo "[FAIL] driver died with daemon"; fi
fresh_daemon
BEFORE=$(wc -l < "$LOG")
send_and_capture "ATTACH $DRV3 usdt $USDT_SO"
check "fresh epoch re-attach ok" "OK ATTACHED" "$REPLY_LINE"
sleep 2
AFTER=$(wc -l < "$LOG")
(( AFTER > BEFORE )) && { PASS=$((PASS+1)); echo "[ok]   fresh epoch emits events"; } \
                      || { FAIL=$((FAIL+1)); echo "[FAIL] no events in fresh epoch"; }
kill "$DRV3" 2>/dev/null; wait "$DRV3" 2>/dev/null

# Graceful shutdown:
send_and_capture "SHUTDOWN"
check "shutdown ok" "OK BYE" "$REPLY_LINE"
for _ in $(seq 1 40); do
    kill -0 "$DAEMON_PID" 2>/dev/null || break
    sleep 0.05
done
if ! kill -0 "$DAEMON_PID" 2>/dev/null; then PASS=$((PASS+1)); echo "[ok]   daemon exited"
else FAIL=$((FAIL+1)); echo "[FAIL] daemon still alive after SHUTDOWN"; fi

LAST_FAIL=$FAIL
echo "== RESULT impl=kt pass=$PASS fail=$FAIL =="
[[ $FAIL -eq 0 ]]

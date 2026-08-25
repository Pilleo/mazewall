#!/usr/bin/env bash
# Inner container script for Tier E WP-04: lifecycle/trust suite executed
# against BOTH control-plane implementations while the C oracle exists.
# Replies must match scenario-for-scenario. Expects:
#   /repo  = repository root (Kotlin dist at tier-e-proto/build/install)
#   cwd    = /repo/ebpf-prototype (relative build/ artifact paths)
#   JAVA_BIN, LD_LIBRARY_PATH prepared by caller (run_wp04.sh)
set -uo pipefail

SOCK=/tmp/wp04.sock
LOG=/tmp/wp04_daemon.log
PASS=0; FAIL=0

start_daemon_c() { ./build/wp04_daemon --sock "$SOCK" > "$LOG" 2>&1 & }
start_daemon_kt() {
    LD_LIBRARY_PATH="/work/build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$JAVA_BIN" -cp "$KT_CP" io.mazewall.tierE.daemon.TierEDaemonKt \
        --sock "$SOCK" --verbose > "$LOG" 2>&1 &
}

check() { # name expected_regex actual
    if [[ "$3" =~ $2 ]]; then PASS=$((PASS+1)); echo "[ok]   $1"
    else FAIL=$((FAIL+1)); echo "[FAIL] $1 (wanted /$2/, got '$3')"; fi
}

wait_socket() { for _ in $(seq 1 100); do [[ -S "$SOCK" ]] && return 0; sleep 0.05; done; return 1; }
wait_mapped() { local i=0; until grep -q "$2" "/proc/$1/maps" 2>/dev/null; do ((i++)) || true; [[ $i -gt 150 ]] && return 1; sleep 0.02; done; }

fresh_daemon() { # $1=starter fn name; kills any previous daemon first
    [[ -n "${DAEMON_PID:-}" ]] && kill -9 "$DAEMON_PID" 2>/dev/null && wait "$DAEMON_PID" 2>/dev/null
    rm -f "$SOCK" "$LOG"
    "$1"; DAEMON_PID=$!
    wait_socket || { FAIL=$((FAIL+1)); echo "[FAIL] no socket after start"; }
}

client() { ./build/wp04_client "$SOCK" "$@"; }

run_suite() {
    local impl="$1" starter="$2"
    echo "===== SUITE impl=$impl ====="
    PASS=0; FAIL=0

    fresh_daemon "$starter"
    check "socket perms 0660" "srw-rw----" "$(ls -l "$SOCK")"

    # T-peercred FIRST: no session exists yet
    if command -v setpriv >/dev/null 2>&1; then
        R=$(setpriv --reuid=1000 --regid=1000 --clear-groups \
            bash -c "./build/wp04_client $SOCK STATUS" 2>&1)
        check "peer uid refused" "ERR PEER_UID|ERR CONNECT|ERR RECV" "$R"
    else
        echo "[skip] peercred (no setpriv)"
    fi

    # T-happy path
    ./build/wp03_driver 1000000 ./build/libmazewall_context_usdt.so 3 & DRV=$!
    wait_mapped "$DRV" usdt
    R=$(client ATTACH "$DRV" usdt /work/build/libmazewall_context_usdt.so)
    check "attach ok + buildid" "OK ATTACHED epoch=[0-9]+ buildid=" "$R"

    # T-duplicate controller
    R=$(client STATUS)
    check "busy rejection" "ERR BUSY" "$R"

    # T-detach then re-bind permitted within epoch
    R=$(client DETACH); check "detach ok" "OK DETACHED" "$R"
    R=$(client ATTACH "$DRV" usdt /work/build/libmazewall_context_usdt.so)
    check "re-attach after detach ok" "OK ATTACHED" "$R"

    # T-marker hygiene loud failures (session dies on each failure)
    echo "not an elf" > /tmp/fake.so
    R=$(client DETACH); check "pre-garbage detach" "OK DETACHED" "$R"
    R=$(client ATTACH "$DRV" uprobe /tmp/fake.so)
    check "garbage elf refused terminally" "ERR MARKER_BUILD_ID_UNREADABLE" "$R"
    R=$(client STATUS) # new connection => fresh epoch accepted
    check "session restarts after hygiene kill" "OK ACCEPTED" "$R"
    cp build/libmazewall_context_usdt.so /tmp/copy.so
    R=$(client ATTACH "$DRV" usdt /tmp/copy.so)
    check "unmapped inode refused terminally" "ERR NOT_MAPPED_IN_TARGET" "$R"
    client SHUTDOWN >/dev/null 2>&1; sleep 0.4

    # T-SIGKILL survival + clean re-attach on a live target
    fresh_daemon "$starter"
    ./build/wp03_driver 300000 ./build/libmazewall_context_usdt.so 4 & DRV3=$!
    wait_mapped "$DRV3" usdt
    client ATTACH "$DRV3" usdt /work/build/libmazewall_context_usdt.so >/dev/null
    kill -9 "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null
    sleep 0.4
    if kill -0 "$DRV3" 2>/dev/null; then PASS=$((PASS+1)); echo "[ok]   driver survived daemon SIGKILL"
    else FAIL=$((FAIL+1)); echo "[FAIL] driver died with daemon"; fi
    fresh_daemon "$starter"
    R=$(client ATTACH "$DRV3" usdt /work/build/libmazewall_context_usdt.so)
    check "fresh epoch re-attach ok" "OK ATTACHED" "$R"
    BEFORE=$(wc -l < "$LOG"); sleep 2; AFTER=$(wc -l < "$LOG")
    (( AFTER > BEFORE )) && { PASS=$((PASS+1)); echo "[ok]   fresh epoch emits events"; } \
                          || { FAIL=$((FAIL+1)); echo "[FAIL] no events in fresh epoch"; }
    kill "$DRV3" 2>/dev/null; wait "$DRV3" 2>/dev/null

    # T-graceful shutdown
    R=$(client SHUTDOWN); check "shutdown ok" "OK BYE" "$R"
    for _ in $(seq 1 40); do
        kill -0 "$DAEMON_PID" 2>/dev/null || break
        sleep 0.05
    done
    if ! kill -0 "$DAEMON_PID" 2>/dev/null; then PASS=$((PASS+1)); echo "[ok]   daemon exited"
    else FAIL=$((FAIL+1)); echo "[FAIL] daemon still alive after SHUTDOWN"; fi

    LAST_FAIL=$FAIL
    echo "== RESULT impl=$impl pass=$PASS fail=$FAIL =="
    return $([[ $FAIL -eq 0 ]] && echo 0 || echo 1)
}

LAST_FAIL=99
run_suite c   start_daemon_c;   RC_C=$?
C_FAIL=$LAST_FAIL
run_suite kt  start_daemon_kt;  RC_KT=$?
K_FAIL=$LAST_FAIL

echo "== FINAL c(rc=$RC_C fail=$C_FAIL) kt(rc=$RC_KT fail=$K_FAIL) =="
if [[ $RC_C -eq 0 && $RC_KT -eq 0 && $C_FAIL -eq $K_FAIL ]]; then
    echo "== PARITY OK =="
    exit 0
fi
echo "== PARITY FAILED =="
exit 1

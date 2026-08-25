#!/usr/bin/env bash
# Inner container script for Tier E WP-04: session lifecycle & trust tests.
# Expects /work on ebpf-prototype/ with prebuilt artifacts. Runs as root.
set -uo pipefail

SOCK=/tmp/wp04.sock
LOG=/tmp/wp04_daemon.log
PASS=0; FAIL=0
check() { # name expected_substr actual
    if [[ "$3" == *"$2"* ]]; then PASS=$((PASS+1)); echo "[ok]   $1"
    else FAIL=$((FAIL+1)); echo "[FAIL] $1 (wanted '$2', got '$3')"; fi
}

start_daemon() {
    rm -f "$SOCK" "$LOG"
    ./build/wp04_daemon --sock "$SOCK" > "$LOG" 2>&1 &
    DAEMON=$!
    for _ in $(seq 1 50); do [[ -S $SOCK ]] && break; sleep 0.05; done
}
stop_daemon() { kill -TERM "$DAEMON" 2>/dev/null; wait "$DAEMON" 2>/dev/null; }

echo "== T0 daemon starts =="
start_daemon
[[ -S "$SOCK" ]] && { PASS=$((PASS+1)); echo "[ok]   socket created"; } || { FAIL=$((FAIL+1)); echo "[FAIL] no socket"; }
ls -l "$SOCK" | grep -q "^....rw-rw----\|srw-rw" && { PASS=$((PASS+1)); echo "[ok]   socket perms 0660"; } || { FAIL=$((FAIL+1)); echo "[FAIL] perms: $(ls -l "$SOCK")"; }

echo "== T1 happy path (usdt) =="
./build/wp03_driver 1000000 ./build/libmazewall_context_usdt.so 3 &
DRV=$!
for _ in $(seq 1 150); do grep -q libmazewall_context_usdt /proc/$DRV/maps 2>/dev/null && break; sleep 0.02; done
R=$(./build/wp04_client "$SOCK" ATTACH "$DRV" usdt /work/build/libmazewall_context_usdt.so)
check "attach ok"        "OK ATTACHED"     "$R"
check "buildid reported" "buildid="        "$R"

echo "== T2 duplicate controller rejected =="
R=$(./build/wp04_client "$SOCK" STATUS)
check "busy rejection"   "ERR BUSY"        "$R"

echo "== T3 re-ATTACH inside one epoch rejected =="
R=$(./build/wp04_client "$SOCK" DETACH); check "detach ok" "OK DETACHED" "$R"
R=$(./build/wp04_client "$SOCK" ATTACH "$DRV" usdt /work/build/libmazewall_context_usdt.so)
check "no re-bind same epoch" "ERR STATE"  "$R"

echo "== T4 wrong file fails loudly (garbage ELF) =="
echo "not an elf" > /tmp/fake.so
R=$(./build/wp04_client "$SOCK" STATUS) # sanity channel alive
R=$(./build/wp04_client "$SOCK" SHUTDOWN >/dev/null; sleep 0.4; start_daemon
    ./build/wp04_driver 200000 ./build/libmazewall_context_usdt.so 2 & DRV2=$!
    for _ in $(seq 1 100); do grep -q usdt /proc/$DRV2/maps 2>/dev/null && break; sleep 0.02; done
    ./build/wp04_client "$SOCK" ATTACH "$DRV2" uprobe /tmp/fake.so)
check "garbage elf refused" "ERR BUILD_ID_UNREADABLE" "$R"

echo "== T5 valid-but-unmapped copy refused =="
cp build/libmazewall_context_usdt.so /tmp/copy.so
R=$(./build/wp04_client "$SOCK" ATTACH "$DRV2" usdt /tmp/copy.so)
check "unmapped inode refused" "ERR NOT_MAPPED_IN_TARGET" "$R"
kill "$DRV2" 2>/dev/null; wait "$DRV2" 2>/dev/null
stop_daemon

echo "== T6 peercred: non-root controller refused =="
start_daemon
if command -v setpriv >/dev/null 2>&1; then
    R=$(setpriv --reuid=1000 --regid=1000 --clear-groups \
        ./build/wp04_client "$SOCK" STATUS)
    check "peer uid rejected" "ERR PEER_UID" "$R"
else
    echo "[skip] setpriv unavailable"
fi

echo "== T7 kill -9 daemon mid-session: target survives, new epoch clean =="
./build/wp03_driver 300000 ./build/libmazewall_context_usdt.so 4 &
DRV3=$!
for _ in $(seq 1 150); do grep -q usdt /proc/$DRV3/maps 2>/dev/null && break; sleep 0.02; done
./build/wp04_client "$SOCK" ATTACH "$DRV3" usdt /work/build/libmazewall_context_usdt.so >/dev/null
kill -9 "$DAEMON"; wait "$DAEMON" 2>/dev/null
sleep 0.5
if kill -0 "$DRV3" 2>/dev/null; then PASS=$((PASS+1)); echo "[ok]   driver survived daemon SIGKILL"
else FAIL=$((FAIL+1)); echo "[FAIL] driver died with daemon"; fi
# New daemon = NEW epoch; attach the SAME live driver and expect fresh events only.
start_daemon
R=$(./build/wp04_client "$SOCK" ATTACH "$DRV3" usdt /work/build/libmazewall_context_usdt.so)
check "re-attach after crash ok" "OK ATTACHED epoch=" "$R"
BEFORE=$(wc -l < "$LOG")
sleep 2
AFTER=$(wc -l < "$LOG")
(( AFTER > BEFORE )) && { PASS=$((PASS+1)); echo "[ok]   fresh epoch emits attributed events"; } \
                     || { FAIL=$((FAIL+1)); echo "[FAIL] no events in fresh epoch"; }
kill "$DRV3" 2>/dev/null; wait "$DRV3" 2>/dev/null

echo "== T8 SHUTDOWN is graceful =="
R=$(./build/wp04_client "$SOCK" SHUTDOWN)
check "shutdown ok" "OK BYE" "$R"
wait "$DAEMON" 2>/dev/null; DRC=$?
[[ $DRC -eq 0 ]] && { PASS=$((PASS+1)); echo "[ok]   daemon exit code 0"; } \
                 || { FAIL=$((FAIL+1)); echo "[FAIL] daemon rc=$DRC"; }
[[ ! -S "$SOCK" ]] && { PASS=$((PASS+1)); echo "[ok]   socket unlinked on exit"; } \
                  || { FAIL=$((FAIL+1)); echo "[FAIL] stale socket left behind"; }

echo "== RESULT pass=$PASS fail=$FAIL =="
[[ $FAIL -eq 0 ]]

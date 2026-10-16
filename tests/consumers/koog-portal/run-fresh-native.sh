#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
root=$(cd -- "$here/../../.." && pwd)
lock=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock
if [[ ${1:-} != --locked ]]; then
    exec flock "$lock" bash "$0" --locked "${1:-java}"
fi
java=${2:-java}
# shellcheck source=fresh-output-ledger.sh
source "$here/fresh-output-ledger.sh"
native_pid=''
native_launch_pending=0
native_wait_complete=0
stop_native_group() {
    # The sole asynchronous launch is owned before effect; $! covers the assignment window.
    if [[ -z $native_pid && $native_launch_pending == 1 ]]; then native_pid=${!:-}; fi
    [[ -n $native_pid ]] || return 0
    # Before setsid establishes the group, terminate the exact still-owned launcher PID.
    # Never send a positive-PID signal after our ordinary wait has reaped that leader.
    if [[ $native_wait_complete == 0 ]] && ! kill -0 -- "-$native_pid" 2>/dev/null && kill -0 "$native_pid" 2>/dev/null; then
        if ! kill -TERM "$native_pid" 2>/dev/null && kill -0 "$native_pid" 2>/dev/null; then
            printf 'NATIVE_LAUNCH_TERM_FAILED pid=%s\n' "$native_pid" >&2
            return 70
        fi
    fi
    if kill -0 -- "-$native_pid" 2>/dev/null; then
        if ! kill -TERM -- "-$native_pid" 2>/dev/null && kill -0 -- "-$native_pid" 2>/dev/null; then
            printf 'NATIVE_GROUP_TERM_FAILED pgid=%s\n' "$native_pid" >&2
            return 70
        fi
        for ((attempt=0; attempt<10; attempt++)); do
            if ! kill -0 -- "-$native_pid" 2>/dev/null; then break; fi
            sleep 0.1
        done
        if kill -0 -- "-$native_pid" 2>/dev/null; then
            if ! kill -KILL -- "-$native_pid" 2>/dev/null && kill -0 -- "-$native_pid" 2>/dev/null; then
                printf 'NATIVE_GROUP_KILL_FAILED pgid=%s\n' "$native_pid" >&2
                return 70
            fi
        fi
    fi
    if [[ $native_wait_complete == 0 ]] && kill -0 "$native_pid" 2>/dev/null; then
        if ! kill -KILL "$native_pid" 2>/dev/null && kill -0 "$native_pid" 2>/dev/null; then
            printf 'NATIVE_LAUNCH_KILL_FAILED pid=%s\n' "$native_pid" >&2
            return 70
        fi
    fi
    # Reap our exact JVM/session leader; other descendant reaping remains its parent/init's job.
    wait "$native_pid" 2>/dev/null || :
}
finish() {
    local primary=$? native_cleanup=0 restoration=0
    trap - EXIT
    trap '' INT TERM HUP
    stop_native_group || native_cleanup=$?
    fresh_restore_all || restoration=$?
    printf 'NATIVE_RUN_END primary=%s native_cleanup=%s restoration=%s\n' "$primary" "$native_cleanup" "$restoration" >&2
    if [[ $primary != 0 ]]; then exit "$primary"; fi
    if [[ $native_cleanup != 0 ]]; then exit "$native_cleanup"; fi
    exit "$restoration"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
fresh=$(mktemp -d /tmp/mazewall-koog-fresh.XXXXXX)
mkdir "$fresh/lib"
cp "$here/build/fresh-probe/lib/"*.jar "$fresh/lib/"
printf 'fresh_directory=%s\n' "$fresh"
# Remove the original output paths and repository while the disposable JVM runs.
# Only curated published JARs were copied; no source/build-directory substitution.
for relative in build platform/build enforcer/build profiler/build portals/runtime/build portals/worker/build portals/codegen/build integrations/koog/build demos/koog-contained-tool/build demos/koog-contained-tool/service-api/build tests/consumers/koog-portal/build tests/consumers/koog-portal/denied-api/build; do
    original="$root/$relative"
    if [[ -d $original ]]; then
        backup="${original}.task7-hidden.$$"
        fresh_hide "$original" "$backup"
    fi
done
cd "$fresh"
# A dedicated session owns the JVM and its worker descendants during catchable signal cleanup.
native_launch_pending=1
setsid "$java" --enable-native-access=ALL-UNNAMED -cp "$fresh/lib/*" io.mazewall.consumer.koog.KoogPortalProbeKt &
native_pid=$!
native_launch_pending=0
native_status=0
wait "$native_pid" || native_status=$?
native_wait_complete=1
exit "$native_status"

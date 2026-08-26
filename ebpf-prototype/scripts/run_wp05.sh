#!/usr/bin/env bash
# Tier E WP-05 / Gate G2 runner — pure Kotlin, zero C.
#
# Env overrides:
#   STRESS_WORKERS(8) STRESS_CHURN(200) STRESS_SYSCALLS(50) JAVA_BIN(java)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EPROOT="$ROOT/ebpf-prototype"

source "$EPROOT/scripts/_backend.sh"
_tier_e_detect_backend

echo "[tier-e] host prebuilds: :profiler installDist"
( cd "$ROOT" && ./gradlew --no-configuration-cache :profiler:installDist -q )

IMAGE="localhost/tier-e-kt-runner"
if ! "${BACKEND[@]}" image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "[tier-e] building $IMAGE ..."
    "${BACKEND[@]}" build -t "$IMAGE" -f "$EPROOT/container/Containerfile.kt-runner" "$ROOT"
fi

echo "[tier-e] backend: ${BACKEND[*]}"
export JAVA_BIN="${JAVA_BIN:-java}"
export KT_CP="/repo/profiler/build/install/tier-e-daemon/lib/*"
export STRESS_WORKERS="${STRESS_WORKERS:-8}"
export STRESS_CHURN="${STRESS_CHURN:-200}"
export STRESS_SYSCALLS="${STRESS_SYSCALLS:-50}"
exec "${BACKEND[@]}" run --rm --privileged --userns=host --pid=host --network=host \
    -e JAVA_BIN="$JAVA_BIN" -e KT_CP \
    -e STRESS_WORKERS -e STRESS_CHURN -e STRESS_SYSCALLS \
    -v "$ROOT":/repo -w /repo/ebpf-prototype \
    "$IMAGE" bash /repo/ebpf-prototype/scripts/_container_inner_wp05.sh

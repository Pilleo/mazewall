#!/usr/bin/env bash
# Tier E WP-05 / Gate G2 runner. Host prebuilds everything, then executes the
# stress + verification phase inside a privileged container.
#
# Env overrides:
#   STRESS_WORKERS(8) STRESS_CHURN(2000) STRESS_NEST(8)
#   STRESS_POOL(4) STRESS_TASKS(80) JAVA_BIN(java)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EPROOT="$ROOT/ebpf-prototype"

source "$EPROOT/scripts/_backend.sh"
_tier_e_detect_backend

echo "[tier-e] host prebuilds: make + boundary gate + :tier-e-proto check/installDist"
make -C "$EPROOT" CC="${CC_OVERRIDE:-clang}" -s
"$ROOT/tier-e-proto/check-boundaries.sh"
( cd "$ROOT" && ./gradlew --no-configuration-cache :profiler:test :profiler:installDist -q )

IMAGE="localhost/tier-e-kt-runner"
if ! "${BACKEND[@]}" image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "[tier-e] building $IMAGE ..."
    "${BACKEND[@]}" build -t "$IMAGE" -f "$EPROOT/container/Containerfile.kt-runner" "$ROOT"
fi

echo "[tier-e] backend: ${BACKEND[*]}"
export JAVA_BIN="${JAVA_BIN:-java}"
export STRESS_WORKERS="${STRESS_WORKERS:-8}"
export STRESS_CHURN="${STRESS_CHURN:-2000}"
export STRESS_NEST="${STRESS_NEST:-8}"
export STRESS_POOL="${STRESS_POOL:-4}"
export STRESS_TASKS="${STRESS_TASKS:-80}"
export TIER_E_RB_DEBUG="${TIER_E_RB_DEBUG:-}"
exec "${BACKEND[@]}" run --rm --privileged --userns=host --pid=host --network=host \
    -e JAVA_BIN="$JAVA_BIN" -e KT_CP="/repo/profiler/build/install/tier-e-daemon/lib/*" \
    -e STRESS_WORKERS -e STRESS_CHURN -e STRESS_NEST -e STRESS_POOL -e STRESS_TASKS \
    -v "$ROOT":/repo -w /repo/ebpf-prototype \
    "$IMAGE" bash /repo/ebpf-prototype/scripts/_container_inner_wp05.sh

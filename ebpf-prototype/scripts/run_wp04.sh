#!/usr/bin/env bash
# Tier E WP-04 runner: lifecycle/trust suite against BOTH implementations.
# Host prebuilds everything (make + gradle installDist), then executes inside
# a privileged container using the shared backend policy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"      # repository root
EPROOT="$ROOT/ebpf-prototype"

source "$EPROOT/scripts/_backend.sh"
_tier_e_detect_backend

echo "[tier-e] host prebuilds: make + :tier-e-proto:installDist"
make -C "$EPROOT" CC="${CC_OVERRIDE:-clang}" -s
( cd "$ROOT" && ./gradlew -PincludeTierEProto=true :tier-e-proto:installDist -q )

IMAGE="localhost/tier-e-kt-runner"
if ! "${BACKEND[@]}" image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "[tier-e] building $IMAGE ..."
    "${BACKEND[@]}" build --pull=missing -t "$IMAGE"         -f "$EPROOT/container/Containerfile.kt-runner" "$ROOT"
fi

echo "[tier-e] backend: ${BACKEND[*]}"
export JAVA_BIN="java"
mkdir -p "$ROOT/tier-e-proto/build"
exec "${BACKEND[@]}" run --rm --privileged --userns=host --pid=host --network=host \
    -e JAVA_BIN="$JAVA_BIN" \
    -e KT_CP="/repo/tier-e-proto/build/install/tier-e-proto/lib/*" \
    -v "$ROOT":/repo \
    -w /repo/ebpf-prototype \
    "$IMAGE" \
    bash /repo/ebpf-prototype/scripts/_container_inner_wp04.sh

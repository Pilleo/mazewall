#!/usr/bin/env bash
# Tier E WP-02 kernel-phase runner.
#
# Loading tracing BPF requires uid=0 with capabilities in the INITIAL user
# namespace. A rootless container engine cannot provide that regardless of
# --privileged (namespaced CAP_BPF is checked against the init userns and
# fails with EPERM; see docs/internals/designs/profiler/tier-e-design.md §Tier P
# and EbpfLoad in :profiler).
#
# Backend selection, in order:
#   1. already root            -> podman directly
#   2. rootful docker daemon   -> docker run (no sudo needed)
#   3. otherwise               -> ONE `sudo podman` invocation
#
# NOTE: rootless and rootful podman keep SEPARATE image stores. The runner
# image is therefore ensured against the SELECTED backend, never assumed from
# another store.
#
# Usage: scripts/run_collector.sh [--duration N]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DURATION=5
[[ "${1:-}" == "--duration" && -n "${2:-}" ]] && { DURATION="$2"; shift 2; }
[[ $# -eq 0 ]] || { echo "usage: $0 [--duration N]" >&2; exit 2; }

if [[ "$(id -u)" -eq 0 ]]; then
    BACKEND=(podman)
elif docker info >/dev/null 2>&1; then
    # Rootless services map container-root to a nonzero host uid
    # (uid_map "0 <hostuid> 1"); rootful maps 0 -> 0 fully.
    probe="$(docker run --rm localhost/tier-e-runner cat /proc/self/uid_map 2>/dev/null | head -1 | awk '{print $1"/"$2}' || true)"
    if [[ "$probe" == "0/0" ]]; then
        echo "[tier-e] using rootful docker daemon"
        BACKEND=(docker)
    else
        echo "[tier-e] docker daemon is ROOTLESS (uid_map ${probe:-unknown}): namespaced caps cannot load tracing BPF." >&2
        BACKEND=(sudo podman)
    fi
else
    BACKEND=(sudo podman)
fi

IMAGE="localhost/tier-e-runner"
if ! "${BACKEND[@]}" image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "[tier-e] building $IMAGE in the selected backend's store ..."
    "${BACKEND[@]}" build --pull=missing -t "$IMAGE" -f "$ROOT/container/Containerfile" "$ROOT"
fi

echo "[tier-e] backend: ${BACKEND[*]}"
exec "${BACKEND[@]}" run \
    --rm \
    --privileged \
    --userns=host \
    --pid=host \
    --network=host \
    -e DURATION="$DURATION" \
    -v "$ROOT":/work \
    -w /work \
    "$IMAGE" \
    bash /work/scripts/_container_inner.sh

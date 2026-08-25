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
#   1. already root            -> podman/docker directly
#   2. rootful docker daemon   -> docker run (no sudo needed)
#   3. otherwise               -> ONE `sudo podman run` (password prompt)
#
# Usage: scripts/run_collector.sh [--duration N]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DURATION=5
[[ "${1:-}" == "--duration" && -n "${2:-}" ]] && { DURATION="$2"; shift 2; }
[[ $# -eq 0 ]] || { echo "usage: $0 [--duration N]" >&2; exit 2; }

IMAGE="localhost/tier-e-runner"
if ! podman image inspect "$IMAGE" >/dev/null 2>&1 && ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "Building $IMAGE ..."
    podman build --pull=missing -t "$IMAGE" -f "$ROOT/container/Containerfile" "$ROOT"
fi

RUN_ARGS=(--rm --privileged --userns=host --pid=host --network=host
          -e DURATION="$DURATION"
          -v "$ROOT":/work -w /work
          "$IMAGE"
          bash /work/scripts/_container_inner.sh)

if [[ "$(id -u)" -eq 0 ]]; then
    exec podman run "${RUN_ARGS[@]}"
fi

# Is the docker-emulating daemon rootful? Rootless services map container-root
# to a nonzero host uid (uid_map "0 <hostuid> 1"); rootful maps 0 -> 0 fully.
if docker info >/dev/null 2>&1; then
    probe="$(docker run --rm --userns=host "$IMAGE" cat /proc/self/uid_map | head -1 | awk '{print $1"/"$2}')"
    if [[ "$probe" == "0/0" ]]; then
        echo "[tier-e] using rootful docker daemon"
        exec docker run "${RUN_ARGS[@]}"
    fi
    echo "[tier-e] docker daemon is ROOTLESS (uid_map $probe): namespaced caps cannot load tracing BPF." >&2
fi

echo "[tier-e] falling back to one 'sudo podman run' (privileged kernel phase requires initial-userns root)" >&2
exec sudo podman run "${RUN_ARGS[@]}"

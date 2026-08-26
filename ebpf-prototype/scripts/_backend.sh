#!/usr/bin/env bash
# Shared container-backend detection for Tier E kernel-phase runners.
# Sources this file; provides: BACKEND array + ensure_runner_image.
# See scripts/run_collector.sh header for the privilege rationale.

_tier_e_detect_backend() {
    # Environment hygiene: a stale DOCKER_HOST pointing at a ROOTLESS podman
    # service silently defeats rootful-docker detection (uid_map 0/<hostuid>).
    # When the real daemon socket exists, prefer it explicitly.
    if [[ "${DOCKER_HOST:-}" == *podman* && -S /var/run/docker.sock ]]; then
        unset DOCKER_HOST
    fi
    if [[ "$(id -u)" -eq 0 ]]; then
        BACKEND=(podman)
        return
    fi
    if docker info >/dev/null 2>&1; then
        # Rootless services map container-root to a nonzero host uid; rootful is 0/0.
        # Probe with a public image: a fresh rootful store has nothing local.
        local probe
        probe="$(docker run --rm docker.io/library/alpine:latest cat /proc/self/uid_map 2>/dev/null \
                 | head -1 | awk '{print $1"/"$2}' || true)"
        if [[ "$probe" == "0/0" ]]; then
            echo "[tier-e] using rootful docker daemon" >&2
            BACKEND=(docker)
            return
        fi
        echo "[tier-e] docker daemon is ROOTLESS (uid_map ${probe:-unknown}); falling back." >&2
    fi
    BACKEND=(sudo podman)
}

_ensure_runner_image() {
    IMAGE="localhost/tier-e-runner"
    if ! "${BACKEND[@]}" image inspect "$IMAGE" >/dev/null 2>&1; then
        echo "[tier-e] building $IMAGE in the selected backend's store ..."
        "${BACKEND[@]}" build -t "$IMAGE" -f "$ROOT/container/Containerfile" "$ROOT"
    fi
}

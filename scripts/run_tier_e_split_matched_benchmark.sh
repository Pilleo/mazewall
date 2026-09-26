#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${TIER_E_SPLIT_MATCHED_OUT:-"${ROOT}/build/tier-e-split-matched"}
FORKS=${TIER_E_SPLIT_MATCHED_FORKS:-5}
ITERATIONS=${TIER_E_SPLIT_MATCHED_ITERATIONS:-200}
MODES=${TIER_E_SPLIT_MATCHED_MODES:-baseline,dictionary-full-stream,dictionary-unique-edge,inline-full-stream,inline-unique-edge,user-notif-no-stack,user-notif-stack}
WORKLOAD_CPU=${TIER_E_SPLIT_WORKLOAD_CPU:-0}
COLLECTOR_CPU=${TIER_E_SPLIT_COLLECTOR_CPU:-1}
if [[ "${TIER_E_SPLIT_CHILD_TIMEOUT_SEC:-540}" != 540 || "${TIER_E_SPLIT_FIXTURE_TIMEOUT_SEC:-600}" != 600 || "${TIER_E_SPLIT_TIMEOUT_KILL_AFTER_SEC:-30}" != 30 ]]; then
    echo 'matched timeout overrides are not owned by the Kotlin scheduler' >&2
    exit 2
fi
if [[ "${TIER_E_SPLIT_CAPTURE_PROFILE:-full}" != full || "${TIER_E_SPLIT_ATTACHMENT_BACKEND:-auto}" != auto ]]; then
    echo 'capture profile and attachment backend overrides are not owned by the Kotlin scheduler' >&2
    exit 2
fi
[[ "${FORKS}" =~ ^[1-9][0-9]*$ && "${ITERATIONS}" =~ ^[1-9][0-9]*$ ]] || exit 2
export MAZEWALL_DOCKER_HOST=${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}
export DOCKER_HOST="${MAZEWALL_DOCKER_HOST}"
if [[ "${TIER_E_SPLIT_REUSE_BUILD:-0}" != 1 ]]; then
    "${ROOT}/gradlew" :profiler:installDist >/dev/null
fi
exec bash "${ROOT}/scripts/tier_e_tools" matched-matrix "${ROOT}/config/testing/tier-e/smoke.json" "${ROOT}" "${OUTPUT}" "${FORKS}" "${ITERATIONS}" "${MODES}" "${WORKLOAD_CPU}" "${COLLECTOR_CPU}"

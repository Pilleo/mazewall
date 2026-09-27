#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -le 2 ]] || { echo 'usage: run_tier_e_node_noise_probe.sh [artifact-directory] [profile.json]' >&2; exit 2; }
OUTPUT=${1:-"${ROOT}/build/tier-e-node-noise-probe"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
if [[ $# == 2 ]]; then
    exec bash "${ROOT}/scripts/tier_e_tools" node-noise-profile "$2" "${ROOT}" "${OUTPUT}"
fi
exec bash "${ROOT}/scripts/tier_e_tools" node-noise-run "${ROOT}" "${OUTPUT}"

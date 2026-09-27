#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -le 2 ]] || { echo 'usage: run_tier_e_rootful_gate.sh [profile.json] [phase-artifacts] (set TIER_E_ARTIFACT_DIR)' >&2; exit 2; }
OUTPUT=${TIER_E_ARTIFACT_DIR:-"${ROOT}/build/tier-e-evidence"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
if [[ $# == 2 ]]; then
    exec bash "${ROOT}/scripts/tier_e_tools" rootful-gate-profile "${ROOT}" "${OUTPUT}" "$1" "$2"
fi
exec bash "${ROOT}/scripts/tier_e_tools" rootful-gate-profile "${ROOT}" "${OUTPUT}" "${1:-${ROOT}/config/testing/tier-e/rootful-gate.json}"

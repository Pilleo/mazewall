#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# == 0 ]] || { echo 'usage: run_tier_e_rootful_gate.sh (set TIER_E_ARTIFACT_DIR)' >&2; exit 2; }
OUTPUT=${TIER_E_ARTIFACT_DIR:-"${ROOT}/build/tier-e-evidence"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" rootful-gate "${ROOT}" "${OUTPUT}"

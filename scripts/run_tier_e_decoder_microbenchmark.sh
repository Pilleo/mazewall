#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -le 1 ]] || { echo 'usage: run_tier_e_decoder_microbenchmark.sh [artifact-directory]' >&2; exit 2; }
OUTPUT=${1:-"${ROOT}/build/tier-e-ring-decoder-microbenchmark"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" ring-microbenchmark decoder "${ROOT}" "${OUTPUT}"

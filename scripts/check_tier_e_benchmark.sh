#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -ge 1 && $# -le 3 ]] || { echo 'usage: check_tier_e_benchmark.sh <native-build-dir> [baseline] [output]' >&2; exit 2; }
BUILD_DIR=$1
BASELINE=${2:-"${ROOT}/profiler-native/tier-e-benchmark-baseline.env"}
OUTPUT=${3:-"${BUILD_DIR}/tier-e-benchmark.log"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" native-phase-gate "${BUILD_DIR}" "${BASELINE}" "${OUTPUT}"

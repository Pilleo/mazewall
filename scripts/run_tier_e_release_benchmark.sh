#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# == 1 || $# == 2 ]] || { echo 'usage: run_tier_e_release_benchmark.sh <native-build-dir> [output-file]' >&2; exit 2; }
BUILD_DIR=$1
OUTPUT=${2:-"${BUILD_DIR}/tier-e-release-benchmark.log"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" native-scenarios release "${ROOT}" "${BUILD_DIR}" "${OUTPUT}"

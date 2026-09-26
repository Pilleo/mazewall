#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# == 1 || $# == 2 ]] || { echo 'usage: compare_tier_e_stack_transports.sh <native-build-dir> [output-file]' >&2; exit 2; }
BUILD_DIR=$1
OUTPUT=${2:-"${BUILD_DIR}/tier-e-stack-transport-comparison.log"}
"${ROOT}/gradlew" :profiler:installDist >/dev/null
exec bash "${ROOT}/scripts/tier_e_tools" native-transports "${BUILD_DIR}" "${OUTPUT}"

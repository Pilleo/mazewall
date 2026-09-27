#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
usage() {
    echo 'usage: build_tier_e_benchmark_bundle.sh [--fingerprint <repository> <usdt>] [--write-manifest <bundle> [fingerprint]] [--verify <bundle> [fingerprint]] [--rebuild] <bundle> [usdt]' >&2
    exit 2
}
if [[ $# -ge 1 ]]; then
    case "$1" in
        --fingerprint) [[ $# == 3 ]] || usage ;;
        --write-manifest|--verify) [[ $# == 2 || $# == 3 ]] || usage ;;
        --rebuild) [[ $# == 2 || $# == 3 ]] || usage ;;
        *) [[ $# == 1 || $# == 2 ]] || usage ;;
    esac
else
    usage
fi
# Bootstrap tooling from the checkout, not from the bundle being constructed.
CACHE_ROOT=${MAZEWALL_TIER_E_BUILD_CACHE_ROOT:-"${ROOT}/build/tier-e-benchmark-cache"}
mkdir -p "${CACHE_ROOT}/gradle-home" "${CACHE_ROOT}/gradle-bootstrap-project-cache"
GRADLE_USER_HOME="${CACHE_ROOT}/gradle-home" "${ROOT}/gradlew" --no-daemon \
    --project-cache-dir "${CACHE_ROOT}/gradle-bootstrap-project-cache" :profiler:installDist >/dev/null
case "$1" in
    --fingerprint)
        exec bash "${ROOT}/scripts/tier_e_tools" bundle-fingerprint "$2" "$3"
        ;;
    --verify)
        exec bash "${ROOT}/scripts/tier_e_tools" bundle-verify "${@:2}"
        ;;
    --write-manifest)
        exec bash "${ROOT}/scripts/tier_e_tools" bundle-write-manifest "${@:2}"
        ;;
    --rebuild)
        bundle=$2
        usdt=${3:-false}
        force=true
        ;;
    *)
        bundle=$1
        usdt=${2:-false}
        force=false
        ;;
esac
repository=${MAZEWALL_TIER_E_REPOSITORY_DIR:-$ROOT}
exec bash "${ROOT}/scripts/tier_e_tools" bundle-build "$repository" "$bundle" "$usdt" "$force"

#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -n "${TIER_E_SPLIT_CREATE_LOCK:-}" ]]; then
    exec bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture_legacy.sh" "$@"
fi
ARTIFACT_DIR=${TIER_E_SPLIT_ARTIFACT_DIR:-"${ROOT}/build/tier-e-split-privilege"}
mkdir -p "${ARTIFACT_DIR}"
exec bash "${ROOT}/scripts/tier_e_tools" fixture-run-env "${ROOT}" "${ARTIFACT_DIR}"

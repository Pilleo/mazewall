#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT=${1:-"${ROOT}/build/tier-e-split-negative-matrix"}
mkdir -p "${OUTPUT}"

# These cases exercise the registered-artifact admission boundary. Each fixture is expected to
# complete its daemon lifecycle but retain an INCOMPLETE, policy-ineligible terminal report.
for scenario in missing malformed symlink unclosed session-mismatch; do
    artifact="${OUTPUT}/${scenario}"
    MAZEWALL_DOCKER_HOST="${MAZEWALL_DOCKER_HOST:-unix:///var/run/docker.sock}" \
        TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE="${scenario}" \
        TIER_E_SPLIT_REUSE_BUILD="${TIER_E_SPLIT_REUSE_BUILD:-0}" \
        TIER_E_SPLIT_ARTIFACT_DIR="${artifact}" \
        bash "${ROOT}/scripts/run_tier_e_split_privilege_fixture.sh"
    export TIER_E_SPLIT_REUSE_BUILD=1
    jq -e '
        .evidence_integrity == "INCOMPLETE" and
        .policy_eligibility == false and
        (.failed_dimensions | length > 0)
    ' "${artifact}/terminal-report.json" > /dev/null
done

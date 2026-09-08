#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/ci.yml"

require() {
    local pattern="$1"
    local message="$2"
    if ! grep -Fq -- "${pattern}" "${workflow}"; then
        echo "${message}" >&2
        exit 1
    fi
}

reject() {
    local pattern="$1"
    local message="$2"
    if grep -Fq -- "${pattern}" "${workflow}"; then
        echo "${message}" >&2
        exit 1
    fi
}

require "cache-read-only: false" \
    "PR reruns must be allowed to save merge-ref-scoped Gradle state"
require "dependency-check-data" \
    "setup-gradle must include OWASP Dependency-Check data"
require "run: ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon" \
    "Dependency-Check must use the host Gradle User Home without configuration cache"
reject "run: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze" \
    "Dependency-Check must not cross the host/container cache boundary"
reject "key: nvd-database-" \
    "an immutable daily NVD key can pin stale data and must not be used"

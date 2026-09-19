#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(cd "${SCRIPT_DIR}/.." && pwd)
BUNDLE_SCRIPT="${SCRIPT_DIR}/build_tier_e_benchmark_bundle.sh"
RUNNER_SCRIPT="${SCRIPT_DIR}/run_tier_e_trustworthy_benchmark.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "${TEST_ROOT}"' EXIT

fingerprint_one=$(bash "${BUNDLE_SCRIPT}" --fingerprint "${REPOSITORY_DIR}" false)
fingerprint_two=$(bash "${BUNDLE_SCRIPT}" --fingerprint "${REPOSITORY_DIR}" false)

[[ "${fingerprint_one}" =~ ^[a-f0-9]{64}$ ]]
[[ "${fingerprint_one}" == "${fingerprint_two}" ]]

bundle_dir="${TEST_ROOT}/bundle"
bundle_fingerprint=$(printf 'a%.0s' {1..64})
different_fingerprint=$(printf 'b%.0s' {1..64})
mkdir -p "${bundle_dir}/runtime"
printf 'agent' > "${bundle_dir}/libmazewall_stack_agent.so"
printf 'runtime' > "${bundle_dir}/runtime/daemon.jar"

bash "${BUNDLE_SCRIPT}" --write-manifest "${bundle_dir}" "${bundle_fingerprint}"
bash "${BUNDLE_SCRIPT}" --verify "${bundle_dir}" "${bundle_fingerprint}"
if bash "${BUNDLE_SCRIPT}" --verify "${bundle_dir}" "${different_fingerprint}"; then
    echo "bundle verification accepted a mismatched input fingerprint" >&2
    exit 1
fi

printf 'modified' > "${bundle_dir}/runtime/daemon.jar"
if bash "${BUNDLE_SCRIPT}" --verify "${bundle_dir}"; then
    echo "bundle verification accepted an altered runtime artifact" >&2
    exit 1
fi

grep -Fq 'build_tier_e_benchmark_bundle.sh' "${RUNNER_SCRIPT}"
grep -Fq 'mkdir -p "${BUILD_CACHE_DIR}"' "${RUNNER_SCRIPT}"
grep -Fq 'ARTIFACT_DIR=$(cd "${ARTIFACT_DIR}" && pwd)' "${RUNNER_SCRIPT}"
grep -Fq 'runtime-${bundle_fingerprint}' "${BUNDLE_SCRIPT}"
grep -Fq 'native-${bundle_fingerprint}' "${BUNDLE_SCRIPT}"
grep -Fq 'enforcer platform profiler profiler-native' "${BUNDLE_SCRIPT}"
grep -Fq 'tier-e-benchmark-bundle-v2' "${BUNDLE_SCRIPT}"
grep -Fq 'now_ms()' "${BUNDLE_SCRIPT}"
if grep -Fq '+%s%3N' "${BUNDLE_SCRIPT}"; then
    echo "bundle script has an ambiguous timestamp format" >&2
    exit 1
fi
grep -Fq '"callback_suppression"' "${RUNNER_SCRIPT}"
grep -Fq '"terminal_while_active"' "${RUNNER_SCRIPT}"
if grep -Fq -- '--rerun-tasks' "${RUNNER_SCRIPT}"; then
    echo "benchmark runner still forces a Gradle rebuild" >&2
    exit 1
fi
if grep -Fq 'mktemp -d /tmp/jseccomp-tier-e-native-build' "${RUNNER_SCRIPT}"; then
    echo "benchmark runner still creates a new native build tree" >&2
    exit 1
fi
grep -Fq ':/bundle:ro' "${RUNNER_SCRIPT}"

#!/usr/bin/env bash
set -euo pipefail

repository=${1:-}
[[ -n "${repository}" ]] || {
    echo "usage: tier_e_benchmark_source_state.sh <repository>" >&2
    exit 2
}
[[ "$(git -C "${repository}" rev-parse --is-inside-work-tree 2>/dev/null || true)" == "true" ]] || {
    echo "repository must be a git checkout" >&2
    exit 2
}

# These paths determine the assembled agent/runtime bundle or how the rootful
# benchmark builds and executes it. Documentation-only edits remain visible in
# all_dirty_count but do not invalidate a content-addressed runtime decision.
benchmark_inputs=(
    build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat
    gradle buildSrc enforcer platform profiler profiler-native
    scripts/build_tier_e_benchmark_bundle.sh
    scripts/run_tier_e_trustworthy_benchmark.sh
    scripts/run_tier_e_sustained_capacity.sh
    scripts/tier_e_benchmark_source_state.sh
)

count_status() {
    awk 'END { print NR + 0 }'
}

all_dirty_count=$(git -C "${repository}" status --porcelain --untracked-files=normal | count_status)
benchmark_input_dirty_count=$(git -C "${repository}" status --porcelain --untracked-files=normal -- "${benchmark_inputs[@]}" | count_status)

jq -n \
    --argjson all_dirty_count "${all_dirty_count}" \
    --argjson benchmark_input_dirty_count "${benchmark_input_dirty_count}" \
    '{schema_version: 1, all_dirty_count: $all_dirty_count, benchmark_input_dirty_count: $benchmark_input_dirty_count}'

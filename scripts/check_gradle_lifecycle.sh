#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/ci.yml"

# Pull-request reruns must be able to reuse the Gradle User Home produced by the
# first attempt. setup-gradle scopes PR caches to the PR merge ref, so allowing
# writes here does not make the entry available to the default branch.
if ! grep -Fq 'uses: gradle/actions/setup-gradle@v6' "${workflow}" \
    || ! grep -Eq '^[[:space:]]+cache-read-only: false$' "${workflow}"; then
    echo "setup-gradle must allow cache writes so reruns of the same PR can restore Gradle state" >&2
    exit 1
fi

if grep -Eq 'actions/cache/(restore|save)@v4' "${workflow}" \
    && grep -Fq '~/.gradle/build-cache' "${workflow}"; then
    echo "manual Gradle build-cache actions duplicate setup-gradle and target the wrong default path" >&2
    exit 1
fi

# Unit verification must remain executable on an ordinary host. Kernel suites are
# intentionally exposed through the separate kernelCheck lifecycle.
modules=(platform enforcer profiler portal)
tasks=()
for module in "${modules[@]}"; do
    tasks+=(":${module}:unitCheck")
done

graph="$(./gradlew "${tasks[@]}" --dry-run --console=plain 2>&1)"

for module in "${modules[@]}"; do
    if grep -Eq "^:${module}:integrationTest(FreshJvm)?( |$)" <<<"${graph}"; then
        echo ":${module}:unitCheck schedules a privileged integration test; use kernelCheck instead" >&2
        exit 1
    fi
done

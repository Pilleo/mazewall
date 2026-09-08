#!/usr/bin/env bash
set -euo pipefail

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

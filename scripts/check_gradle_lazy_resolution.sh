#!/usr/bin/env bash
set -euo pipefail

# Gradle 9 forbids resolving cross-project configurations during configuration.
# Keep `.asPath`/`.singleFile` inside an execution-time provider or task action.
bad=0
while IFS=: read -r file line text; do
    case "$text" in
        *".asPath"*|*".singleFile"*)
            case "$text" in
                *"asArguments"*|*"doFirst"*|*"doLast"*|*"providers.provider"*) ;;
                *) echo "Eager Gradle configuration resolution: $file:$line: $text" >&2; bad=1 ;;
            esac
            ;;
    esac
done < <(rg -n --glob '*.gradle.kts' '\.(asPath|singleFile)\b' \
    --glob '!tools/orchestrator/**' --glob '!build/**' .)

exit "$bad"

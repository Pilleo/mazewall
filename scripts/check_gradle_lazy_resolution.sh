#!/usr/bin/env bash
set -euo pipefail

# Gradle 9 forbids resolving cross-project configurations during configuration.
# Keep resolution and task lookup lazy.
bad=0
while IFS=: read -r file line text; do
    case "$text" in
        *".asPath"*|*".singleFile"*)
            case "$text" in
                *"asArguments"*|*"doFirst"*|*"doLast"*|*"providers.provider"*|*".map"*) ;;
                *) echo "Eager Gradle configuration resolution: $file:$line: $text" >&2; bad=1 ;;
            esac
            ;;
        *"evaluationDependsOn("*|*".resolvedConfiguration"*|*".files.map"*|*"tasks.findByName("*|*"javaClass.getMethod("*)
            echo "Eager or reflective Gradle configuration: $file:$line: $text" >&2
            bad=1
            ;;
    esac
done < <(rg -n --glob '*.gradle.kts' '\.(asPath|singleFile)\b|evaluationDependsOn\(|\.resolvedConfiguration|\.files\.map|tasks\.findByName\(|javaClass\.getMethod\(' \
    --glob '!tools/orchestrator/**' --glob '!build/**' .)

exit "$bad"

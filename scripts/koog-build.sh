#!/usr/bin/env bash
# Two Gradle builds are necessary: the demo buildscript loads the actual plugin JAR.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
shared=()
value_pending=0
while (($#)); do
    if [[ $1 == -- && $value_pending == 0 ]]; then shift; break; fi
    if ((value_pending)); then
        shared+=("$1"); value_pending=0; shift; continue
    fi
    case "$1" in
        -PincludeKoogIntegration*|-PkoogBootstrap*|-PportalCodegenRevision*|--configuration-cache|--configuration-cache=*|-p|--project-dir*|-b|--build-file*|-c|--settings-file*)
            printf 'Bootstrap-owned/conflicting Gradle argument: %s\n' "$1" >&2; exit 64 ;;
        -I|--init-script|-g|--gradle-user-home|--project-cache-dir|--console|--max-workers|--warning-mode|--dependency-verification)
            shared+=("$1"); value_pending=1 ;;
        -*) shared+=("$1") ;;
        *) printf 'Put selected tasks after --; unexpected shared argument: %s\n' "$1" >&2; exit 64 ;;
    esac
    shift
done
if ((value_pending)) || (($# == 0)); then
    printf 'Usage: scripts/koog-build.sh [shared Gradle options] -- <selected tasks/options>\n' >&2
    exit 64
fi
for arg in "$@"; do
    case "$arg" in
        -PincludeKoogIntegration*|-PkoogBootstrap*|-PportalCodegenRevision*|--configuration-cache|--configuration-cache=*|-p|--project-dir*|-b|--build-file*|-c|--settings-file*)
            printf 'Bootstrap-owned/conflicting Gradle argument: %s\n' "$arg" >&2; exit 64 ;;
    esac
done
cd "$root"
revision=$(git rev-parse HEAD)
printf 'KOOG_STAGE producer root=%s revision=%s\n' "$root" "$revision"
"$root/gradlew" "${shared[@]}" --no-configuration-cache \
    -PincludeKoogIntegration=true -PkoogBootstrap=true -PportalCodegenRevision="$revision" \
    :portals:codegen:exportPluginClasspath
printf 'KOOG_STAGE selected revision=%s\n' "$revision"
exec "$root/gradlew" "${shared[@]}" --no-configuration-cache \
    -PincludeKoogIntegration=true -PkoogBootstrap=false "$@"

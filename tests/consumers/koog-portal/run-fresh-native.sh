#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
root=$(cd -- "$here/../../.." && pwd)
lock=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock
if [[ ${1:-} != --locked ]]; then
    exec flock "$lock" bash "$0" --locked "${1:-java}"
fi
java=${2:-java}
fresh=$(mktemp -d /tmp/mazewall-koog-fresh.XXXXXX)
mkdir "$fresh/lib"
cp "$here/build/fresh-probe/lib/"*.jar "$fresh/lib/"
printf 'fresh_directory=%s\n' "$fresh"
declare -a originals=() hidden=()
restore() {
    for ((i=${#originals[@]}-1; i>=0; i--)); do
        mv -- "${hidden[i]}" "${originals[i]}"
    done
}
trap restore EXIT
# Remove the original output paths and repository while the disposable JVM runs.
# Only curated published JARs were copied; no source/build-directory substitution.
for relative in build platform/build enforcer/build profiler/build portals/runtime/build portals/worker/build portals/codegen/build integrations/koog/build demos/koog-contained-tool/build demos/koog-contained-tool/service-api/build tests/consumers/koog-portal/build tests/consumers/koog-portal/denied-api/build; do
    original="$root/$relative"
    if [[ -d $original ]]; then
        backup="${original}.task7-hidden.$$"
        [[ ! -e $backup ]]
        mv -- "$original" "$backup"
        originals+=("$original")
        hidden+=("$backup")
    fi
done
cd "$fresh"
"$java" --enable-native-access=ALL-UNNAMED -cp "$fresh/lib/*" io.mazewall.consumer.koog.KoogPortalProbeKt

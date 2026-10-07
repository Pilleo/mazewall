#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]] || ! command -v gcc >/dev/null 2>&1; then
    printf 'CET fixture cache test requires Linux x86_64 and gcc\n' >&2
    exit 78
fi
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/scripts" "$fixture/enforcer/src/test/c"
cp "$root/scripts/run_cet_demo.sh" "$root/scripts/cet_fixture_fingerprint.sh" "$fixture/scripts/"
cp "$root/enforcer/src/test/c/libvulnerable_rop.c" "$fixture/enforcer/src/test/c/"

# Exercise the actual repository task declaration in an independent Gradle build.
python3 - "$root" "$fixture" <<'PY'
from pathlib import Path
import sys
root, fixture = map(Path, sys.argv[1:])
script = (root / 'enforcer/build.gradle.kts').read_text()
start = script.index('val compileVulnerableRop =')
end = script.index('// issue-20260823-172001:', start)
(fixture / 'build.gradle.kts').write_text(script[start:end])
(fixture / 'settings.gradle.kts').write_text('rootProject.name = "native-fixture-cache-test"\n')
PY

run_fixture() {
    "$root/gradlew" -p "$fixture" compileVulnerableRop --configuration-cache --info >"$fixture/$1.log" 2>&1
}
require_compiled() {
    if ! rg -q 'Compiling vulnerable C library' "$fixture/$1.log"; then
        cat "$fixture/$1.log" >&2
        exit 1
    fi
}
run_fixture first
require_compiled first
run_fixture warm
rg -q '^> Task :compileVulnerableRop UP-TO-DATE' "$fixture/warm.log"

printf '\n/* changed fixture source */\n' >> "$fixture/enforcer/src/test/c/libvulnerable_rop.c"
run_fixture source_changed
require_compiled source_changed

printf '#define CACHE_HEADER_VALUE 1\n' > "$fixture/enforcer/src/test/c/cache_header.h"
printf '\n#include "cache_header.h"\nint cache_header_value = CACHE_HEADER_VALUE;\n' >> "$fixture/enforcer/src/test/c/libvulnerable_rop.c"
run_fixture header_added
require_compiled header_added
printf '#define CACHE_HEADER_VALUE 2\n' > "$fixture/enforcer/src/test/c/cache_header.h"
run_fixture header_changed
require_compiled header_changed

real_compiler="$(command -v gcc)"
mkdir "$fixture/compiler"
printf '#!/usr/bin/env bash\nexec "%s" "$@"\n' "$real_compiler" > "$fixture/compiler/gcc"
chmod +x "$fixture/compiler/gcc"
PATH="$fixture/compiler:$PATH" run_fixture compiler_changed
require_compiled compiler_changed

rm "$fixture/libvulnerable_rop.so"
run_fixture output_missing
require_compiled output_missing
run_fixture final_warm
rg -q '^> Task :compileVulnerableRop UP-TO-DATE' "$fixture/final_warm.log"
printf 'CET fixture cache: unchanged reuse, source/header invalidation and missing-output rebuild passed.\n'

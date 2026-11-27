#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/mazewall-c-abi-oracle.XXXXXXXX")
trap 'status=$?; if (( status == 0 )); then rm -rf -- "$fixture"; else printf "ABI regression logs retained at %s\n" "$fixture" >&2; fi' EXIT
project="$fixture/checkout"
mkdir -p "$project" "$fixture/bin" "$fixture/include"

python3 - "$root" "$project" <<'PY'
import os
from pathlib import Path
import shutil
import subprocess
import sys

root, project = map(Path, sys.argv[1:])
tracked = subprocess.check_output(["git", "ls-files", "-z", "--cached"], cwd=root)
for entry in filter(None, tracked.split(b"\0")):
    path = os.fsdecode(entry)
    source, target = root / path, project / path
    if not source.is_symlink() and not source.is_file():
        continue
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target, follow_symlinks=False)
PY

cat >> "$project/settings.gradle.kts" <<'EOF'

buildCache {
    local { directory = settingsDir.resolve("../gradle-build-cache") }
}
EOF

export MAZEWALL_ABI_FIXTURE="$fixture"
MAZEWALL_ABI_REAL_CC=$(command -v cc)
export MAZEWALL_ABI_REAL_CC
export PATH="$fixture/bin:$PATH"
cat > "$fixture/bin/cc" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=("$@")
for index in "${!args[@]}"; do
    case "${args[index]}" in
        */src/test/resources/abi/layout_oracle.c)
            printf 'compile\n' >> "$MAZEWALL_ABI_FIXTURE/compiles"
            if [[ -f "$MAZEWALL_ABI_FIXTURE/mode" ]]; then
                case "$(cat "$MAZEWALL_ABI_FIXTURE/mode")" in
                    compile-fail)
                        printf 'fixture cc: forced ABI compile failure\n' >&2
                        exit 41
                        ;;
                    probe-fail) args[index]="$MAZEWALL_ABI_FIXTURE/exit.c" ;;
                    *) printf 'Unknown ABI fixture mode\n' >&2; exit 2 ;;
                esac
            fi
            exec "$MAZEWALL_ABI_REAL_CC" -I"$MAZEWALL_ABI_FIXTURE/include" "${args[@]}"
            ;;
    esac
done
exec "$MAZEWALL_ABI_REAL_CC" "$@"
EOF
chmod +x "$fixture/bin/cc"
printf 'int main(void) { return 42; }\n' > "$fixture/exit.c"
printf '#include_next <fcntl.h>\n' > "$fixture/include/fcntl.h"
touch "$fixture/compiles"

layout_test=(:platform:test --tests 'io.mazewall.ffi.CAbiLayoutOracleTest')
stack_test=(:platform:test --tests 'io.mazewall.ffi.internal.RealNativeStackContextsTest.abiOracle matches actual header every member alignment and frame stride')
oracle="$project/platform/build/abi-oracle/layout-oracle"
layout_xml="$project/platform/build/test-results/test/TEST-io.mazewall.ffi.CAbiLayoutOracleTest.xml"

fail() {
    printf 'ABI oracle regression: %s\n' "$*" >&2
    if [[ -n "${log:-}" ]]; then tail -n 65 "$log" >&2; fi
    exit 1
}

run_gradle() {
    local name=$1 expected=$2 outcome=pass
    shift 2
    log="$fixture/$name.log"
    printf 'ABI oracle regression %s: %s\n' "$name" "$log"
    (
        cd "$project"
        ./gradlew --build-cache --configuration-cache --configuration-cache-problems=fail --console=plain "$@"
    ) > "$log" 2>&1 || outcome=fail
    [[ "$outcome" == "$expected" ]] || fail "$name expected Gradle to $expected, got $outcome"
}

require_compiles() {
    local count
    count=$(wc -l < "$fixture/compiles")
    [[ "$count" -eq "$1" ]] || fail "expected $1 oracle compiler invocations, got $count"
}

probe_hash() {
    sha256sum "$oracle" | cut -d' ' -f1
}

run_gradle baseline pass "${layout_test[@]}"
grep -Fxq '> Task :platform:test' "$log" || fail 'baseline did not execute the comparison'
[[ -f "$layout_xml" ]] || fail 'baseline produced no layout comparison result'
"$oracle" > "$fixture/baseline-observations"
grep -Eq '^flag\.O_PATH=[1-9][0-9]*$' "$fixture/baseline-observations" || fail 'baseline O_PATH was not positive'
require_compiles 1
baseline_hash=$(probe_hash)

# The initial build can create inputs observed by PlantUML at configuration time.
run_gradle prime-replay pass "${layout_test[@]}"
run_gradle unchanged pass "${layout_test[@]}"
unchanged_hash=$(probe_hash)
unchanged_count=$(wc -l < "$fixture/compiles")
unchanged_log=$log

printf '\n#undef O_PATH\n#define O_PATH 0\n' >> "$fixture/include/fcntl.h"
run_gradle header-change fail "${layout_test[@]}"
grep -Fq 'Configuration cache entry reused' "$log" || fail 'header change did not reuse the saved task graph'
require_compiles 4
[[ "$(probe_hash)" != "$baseline_hash" ]] || fail 'header change left the executable unchanged'
grep -Fxq '> Task :platform:test FAILED' "$log" || fail 'header change did not fail the test task'
grep -Fq 'C ABI mismatch for flag.O_PATH' "$layout_xml" || fail 'header change produced no O_PATH mismatch'
grep -Fq 'but was: &lt;0&gt;' "$layout_xml" || fail 'the test did not report the fixture value 0'

log=$unchanged_log
grep -Fq 'Configuration cache entry reused' "$log" || fail 'unchanged repeat did not reuse the saved task graph'
[[ "$unchanged_count" -eq 3 && "$unchanged_hash" == "$baseline_hash" ]] || fail 'unchanged repeat did not rebuild the same executable'
grep -Eq '^> Task :platform:test (UP-TO-DATE|FROM-CACHE)$' "$log" || fail 'unchanged repeat did not reuse the comparison'

printf '#include_next <fcntl.h>\n' > "$fixture/include/fcntl.h"
run_gradle restored pass "${layout_test[@]}"
[[ "$(probe_hash)" == "$baseline_hash" ]] || fail 'restored header did not reproduce the baseline binary'
require_compiles 5

printf 'compile-fail\n' > "$fixture/mode"
for consumer in test verifyCAbi; do
    if [[ "$consumer" == test ]]; then
        run_gradle compiler-failure-test fail "${layout_test[@]}"
    else
        run_gradle compiler-failure-standalone fail :platform:verifyCAbi
    fi
    grep -Fxq '> Task :platform:compileCAbiOracle FAILED' "$log" || fail 'compiler error did not fail the compile task'
    grep -Fq 'fixture cc: forced ABI compile failure' "$log" || fail 'compiler diagnostic missing'
    if grep -Eq "^> Task :platform:$consumer( |$)" "$log"; then fail "$consumer ran after compiler failure"; fi
    [[ "$(probe_hash)" == "$baseline_hash" ]] || fail 'failed compile replaced the successful executable'
done
require_compiles 7

printf 'probe-fail\n' > "$fixture/mode"
run_gradle probe-failure-standalone fail :platform:verifyCAbi
grep -Fxq '> Task :platform:verifyCAbi FAILED' "$log" || fail 'standalone task did not fail on the probe exit'
grep -Fq 'non-zero exit value 42' "$log" || fail 'standalone task did not report the probe exit status'
run_gradle probe-failure-test fail "${layout_test[@]}"
grep -Fxq '> Task :platform:test FAILED' "$log" || fail 'layout comparison did not fail on the probe exit'
grep -Fq 'C ABI oracle failed' "$layout_xml" || fail 'layout comparison did not report probe failure'
grep -Fq 'but was: &lt;42&gt;' "$layout_xml" || fail 'layout comparison did not report exit status 42'
require_compiles 9

rm "$fixture/mode"
run_gradle standalone pass :platform:verifyCAbi
grep -Eq '^flag\.O_PATH=[1-9][0-9]*$' "$log" || fail 'standalone task did not print current observations'
run_gradle stack-context pass "${stack_test[@]}"
grep -Fxq '> Task :platform:test' "$log" || fail 'stack-context comparison did not execute'
require_compiles 11
printf 'ABI oracle cache regression passed.\n'

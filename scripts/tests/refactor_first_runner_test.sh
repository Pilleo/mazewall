#!/usr/bin/env bash

set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
runner="$repo_root/scripts/refactor_first.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/refactor-first-runner-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT

fixture="$test_root/repository"
fake_bin="$test_root/bin"
mkdir -p "$fixture" "$fake_bin"

git -C "$fixture" init -q
git -C "$fixture" config user.name "RefactorFirst Test"
git -C "$fixture" config user.email "refactor-first-test@example.invalid"

scopes=(platform enforcer profiler portals/runtime portals/codegen portals/worker)
for scope in "${scopes[@]}"; do
    mkdir -p "$fixture/$scope/src/main/kotlin/example"
    printf 'package example\nclass %sMain\n' "${scope//[^[:alnum:]]/}" > "$fixture/$scope/src/main/kotlin/example/Main.kt"
done

excluded_paths=(
    "enforcer/src/test/kotlin/example/Test.kt"
    ".worktrees/duplicate/enforcer/src/main/kotlin/example/Duplicate.kt"
    "demos/sample/src/main/kotlin/example/Demo.kt"
    "buildSrc/src/main/java/example/BuildLogic.java"
    "tools/orchestrator/src/main/kotlin/example/Orchestrator.kt"
)
for path in "${excluded_paths[@]}"; do
    mkdir -p "$fixture/$(dirname "$path")"
    printf 'class Excluded\n' > "$fixture/$path"
done
mkdir -p "$fixture/docs"
printf 'fixture\n' > "$fixture/docs/README.md"

git -C "$fixture" add -A
git -C "$fixture" commit -qm "fixture"

cat > "$fake_bin/java" <<'EOF'
#!/usr/bin/env bash
printf 'openjdk version "%s"\n' "${REFACTOR_TEST_JAVA_VERSION:-25.0.2}" >&2
EOF

cat > "$fake_bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-v" ]]; then
    printf '\033[1mApache Maven %s\033[0m\n' "${REFACTOR_TEST_MAVEN_VERSION:-3.9.12}"
    printf 'Java version: %s\n' "${REFACTOR_TEST_JAVA_VERSION:-25.0.2}"
    exit 0
fi
printf '%s\n' "$*" > "$REFACTOR_TEST_ARGUMENTS"
[[ -f pom.xml ]] || exit 18
cp pom.xml "$REFACTOR_TEST_POM"
find . -type f \( -name '*.java' -o -name '*.kt' -o -name '*.kts' \) -print | sort > "$REFACTOR_TEST_SOURCES"
if [[ "${REFACTOR_TEST_MAVEN_SLEEP:-0}" == "1" ]]; then
    sleep 30
fi
if [[ "${REFACTOR_TEST_MAVEN_FAIL:-0}" == "1" ]]; then
    exit 17
fi
mkdir -p target/site
printf '<html><body>RefactorFirst fixture</body></html>\n' > target/site/refactor-first-report.html
EOF
chmod +x "$fake_bin/java" "$fake_bin/mvn"

output="$test_root/output/refactor-first-report.html"
arguments="$test_root/maven-arguments.txt"
sources="$test_root/analyzed-sources.txt"
pom="$test_root/generated-pom.xml"
export PATH="$fake_bin:$PATH"
export TMPDIR="$test_root/tmp"
export REFACTOR_TEST_ARGUMENTS="$arguments"
export REFACTOR_TEST_SOURCES="$sources"
export REFACTOR_TEST_POM="$pom"
mkdir -p "$TMPDIR"

run_report() {
    (cd "$fixture" && "$runner" --output "$output")
}

run_preflight() {
    (cd "$fixture" && "$runner" --check)
}

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

run_preflight
run_report
[[ -f "$output" ]] || fail "report was not published"
rg -q 'refactor-first-maven-plugin:0\.10\.0:htmlReport' "$arguments" || fail "0.10.0 was not invoked"
rg -q '<directory>\$\{project\.basedir\}/target</directory>' "$pom" || fail "generated Maven output was not pinned"

for scope in "${scopes[@]}"; do
    rg -q "^\./$scope/src/main/" "$sources" || fail "$scope production sources were not analyzed"
done
if rg -q '/src/test/|^\./\.worktrees/|^\./demos/|^\./buildSrc/|^\./tools/orchestrator/' "$sources"; then
    fail "excluded sources reached RefactorFirst"
fi
if find "$TMPDIR" -mindepth 1 -print -quit | grep -q .; then
    fail "temporary snapshot remained after success"
fi

printf 'unrelated documentation edit\n' >> "$fixture/docs/README.md"
run_report

printf '// dirty\n' >> "$fixture/enforcer/src/main/kotlin/example/Main.kt"
if run_preflight >"$test_root/dirty-check.out" 2>"$test_root/dirty-check.err"; then
    fail "preflight accepted a dirty scoped production source"
fi
rg -q 'committed HEAD' "$test_root/dirty-check.err" || fail "preflight dirty-source failure was not actionable"
if run_report >"$test_root/dirty.out" 2>"$test_root/dirty.err"; then
    fail "dirty scoped production source was accepted"
fi
rg -q 'committed HEAD' "$test_root/dirty.err" || fail "dirty-source failure was not actionable"
git -C "$fixture" restore enforcer/src/main/kotlin/example/Main.kt

printf 'previous report\n' > "$output"
export REFACTOR_TEST_MAVEN_FAIL=1
if run_report >"$test_root/maven-failure.out" 2>"$test_root/maven-failure.err"; then
    fail "Maven failure was ignored"
fi
unset REFACTOR_TEST_MAVEN_FAIL
[[ $(cat "$output") == "previous report" ]] || fail "failed analysis replaced the previous report"
if find "$TMPDIR" -mindepth 1 -print -quit | grep -q .; then
    fail "temporary snapshot remained after failure"
fi

export REFACTOR_TEST_MAVEN_SLEEP=1
if (cd "$fixture" && timeout --signal=TERM --kill-after=1s 0.2s "$runner" --output "$output") \
    >"$test_root/interrupted.out" 2>"$test_root/interrupted.err"; then
    fail "interrupted analysis unexpectedly succeeded"
fi
unset REFACTOR_TEST_MAVEN_SLEEP
if find "$TMPDIR" -mindepth 1 -print -quit | grep -q .; then
    fail "temporary snapshot remained after interruption"
fi

export REFACTOR_TEST_JAVA_VERSION=20.0.2
if run_report >"$test_root/java-version.out" 2>"$test_root/java-version.err"; then
    fail "Java below 21 was accepted"
fi
rg -q 'Java 21 or newer' "$test_root/java-version.err" || fail "Java preflight failure was not actionable"
unset REFACTOR_TEST_JAVA_VERSION

export REFACTOR_TEST_MAVEN_VERSION=3.9.8
if run_report >"$test_root/maven-version.out" 2>"$test_root/maven-version.err"; then
    fail "Maven below 3.9.9 was accepted"
fi
rg -q 'Maven 3\.9\.9 or newer' "$test_root/maven-version.err" || fail "Maven preflight failure was not actionable"

printf 'RefactorFirst runner tests passed.\n'

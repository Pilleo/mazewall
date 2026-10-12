#!/usr/bin/env bash
# Check only pinned Maven publications in an independent consumer build.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
repository=
version=
compiler=2.4.20
evidence="$root/build/integration-consumer-evidence"
write_locks=()
while (($#)); do
    case "$1" in
        --repository) repository=${2:?}; shift 2 ;;
        --version) version=${2:?}; shift 2 ;;
        --kotlin-version) compiler=${2:?}; shift 2 ;;
        --evidence-dir) evidence=${2:?}; shift 2 ;;
        --write-locks) write_locks=(--write-locks); shift ;;
        *) echo "Usage: $0 --repository ABSOLUTE_PATH --version PINNED_VERSION [--kotlin-version STABLE_VERSION] [--evidence-dir PATH] [--write-locks]" >&2; exit 2 ;;
    esac
done
[[ "$repository" = /* && -d "$repository" && -n "$version" ]] || { echo 'An existing absolute Maven repository and pinned version are required' >&2; exit 2; }
[[ "$compiler" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'A stable Kotlin compiler is required' >&2; exit 2; }
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+[-.a-zA-Z0-9]*$ ]] || { echo 'Invalid pinned candidate version' >&2; exit 2; }
common=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir)
lock="$(dirname "$common")/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock"
[[ -d "$(dirname "$lock")" ]] || { echo "Fleet verification lock directory missing: $lock" >&2; exit 2; }
mkdir -p "$evidence"
exec 9>"$lock"
flock 9

git -C "$root" rev-parse HEAD > "$evidence/source-revision.txt"
git -C "$root" status --short > "$evidence/worktree-status.txt"
: > "$evidence/artifact-sha256.txt"
for module in platform enforcer; do
    for extension in jar pom module; do
        artifact="$repository/io/mazewall/$module/$version/$module-$version.$extension"
        [[ -f "$artifact" ]] || { echo "Missing real Maven publication: $artifact" >&2; exit 1; }
        sha256sum "$artifact" >> "$evidence/artifact-sha256.txt"
    done
done
"$root/gradlew" --version > "$evidence/gradle-version.log" 2>&1
: > "$evidence/commands.log"
failed=0
for jdk in 22 25; do
    command=("$root/gradlew" -p "$root/tests/consumers" --console=plain --no-build-cache --no-configuration-cache
        "-PconsumerRepository=$repository" "-PconsumerVersion=$version" "-PconsumerKotlinVersion=$compiler" "-PconsumerJdk=$jdk"
        "${write_locks[@]}" :java-api:clean :kotlin-api:clean
        :java-api:consumerEnvironment :java-api:run :java-api:verifyCallableEntry :java-api:verifyScopeBoundaries :java-api:verifyRetiredApi
        :kotlin-api:consumerEnvironment :kotlin-api:run :kotlin-api:verifyRetiredApi :kotlin-api:verifyScopeBoundaries resolveIntegrationBaselines)
    printf '%q ' "${command[@]}" >> "$evidence/commands.log"
    printf '\n' >> "$evidence/commands.log"
    if "${command[@]}" > "$evidence/jdk$jdk.log" 2>&1; then
        echo "JDK$jdk Java/Callable/scope/stable-Kotlin/version-graphs: PASS"
    else
        echo "JDK$jdk consumer check: FAIL (see $evidence/jdk$jdk.log)" >&2
        failed=1
    fi
done
echo "Evidence: $evidence"
exit "$failed"

#!/usr/bin/env bash

set -euo pipefail

readonly REFACTOR_FIRST_VERSION="0.10.0"
readonly MINIMUM_JAVA_VERSION=21
readonly MINIMUM_MAVEN_VERSION="3.9.9"
readonly SCOPED_SOURCE_ROOTS=(
    "platform/src/main"
    "enforcer/src/main"
    "profiler/src/main"
    "portals/runtime/src/main"
    "portals/codegen/src/main"
    "portals/worker/src/main"
)

usage() {
    printf 'Usage: %s --check | --output <report-file>\n' "$(basename "$0")" >&2
}

fail() {
    printf 'RefactorFirst preflight failed: %s\n' "$1" >&2
    exit 1
}

version_at_least() {
    local actual=$1
    local minimum=$2
    [[ "$(printf '%s\n%s\n' "$minimum" "$actual" | sort -V | head -n 1)" == "$minimum" ]]
}

mode=""
output_argument=""
case "${1:-}" in
    --check)
        [[ $# -eq 1 ]] || { usage; exit 2; }
        mode="check"
        ;;
    --output)
        [[ $# -eq 2 && -n $2 ]] || { usage; exit 2; }
        mode="report"
        output_argument=$2
        ;;
    *)
        usage
        exit 2
        ;;
esac

command -v git >/dev/null 2>&1 || fail "git is required."
command -v java >/dev/null 2>&1 || fail "Java $MINIMUM_JAVA_VERSION or newer is required."
command -v mvn >/dev/null 2>&1 || fail "Maven $MINIMUM_MAVEN_VERSION or newer is required."

java_banner=$(java -version 2>&1 | head -n 1)
java_version=$(sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/' <<<"$java_banner")
[[ $java_version =~ ^[0-9]+$ ]] || fail "Could not parse Java version from: $java_banner"
(( java_version >= MINIMUM_JAVA_VERSION )) || fail \
    "Java $MINIMUM_JAVA_VERSION or newer is required; found $java_banner"

maven_banner=$(mvn -v 2>&1)
maven_banner_plain=$(sed -E $'s/\033\\[[0-9;]*m//g' <<<"$maven_banner")
maven_version=$(sed -nE 's/^Apache Maven ([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' <<<"$maven_banner_plain" | head -n 1)
[[ -n $maven_version ]] || fail "Could not parse Maven version from: ${maven_banner%%$'\n'*}"
version_at_least "$maven_version" "$MINIMUM_MAVEN_VERSION" || fail \
    "Maven $MINIMUM_MAVEN_VERSION or newer is required; found Maven $maven_version."

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || fail "Run this task from a Git checkout."
commit=$(git -C "$repo_root" rev-parse --verify HEAD)

dirty_sources=$(git -C "$repo_root" status --porcelain --untracked-files=all -- "${SCOPED_SOURCE_ROOTS[@]}")
if [[ -n $dirty_sources ]]; then
    printf '%s\n' "$dirty_sources" >&2
    fail "The report describes committed HEAD; commit or stash scoped production-source changes first."
fi

if [[ $mode == "check" ]]; then
    printf 'RefactorFirst preflight passed for committed HEAD %s.\n' "$commit"
    exit 0
fi

mkdir -p "$(dirname "$output_argument")"
output_dir=$(cd "$(dirname "$output_argument")" && pwd -P)
output_file="$output_dir/$(basename "$output_argument")"

temp_root=$(mktemp -d "${TMPDIR:-/tmp}/mazewall-refactor-first.XXXXXX")
snapshot="$temp_root/repository"
publish_tmp=""

cleanup() {
    if [[ -n $publish_tmp ]]; then
        rm -f -- "$publish_tmp"
    fi
    rm -rf -- "$temp_root"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

git clone --quiet --shared --no-checkout -- "$repo_root" "$snapshot"
git -C "$snapshot" sparse-checkout init --no-cone
printf '/%s/\n' "${SCOPED_SOURCE_ROOTS[@]}" | git -C "$snapshot" sparse-checkout set --no-cone --stdin
git -C "$snapshot" checkout --quiet --detach "$commit"

printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<project xmlns="http://maven.apache.org/POM/4.0.0"' \
    '         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' \
    '         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">' \
    '  <modelVersion>4.0.0</modelVersion>' \
    '  <groupId>io.mazewall.analysis</groupId>' \
    '  <artifactId>refactor-first-snapshot</artifactId>' \
    '  <version>1</version>' \
    '  <build>' \
    '    <directory>${project.basedir}/target</directory>' \
    '  </build>' \
    '</project>' > "$snapshot/pom.xml"

(
    cd "$snapshot"
    mvn -B "org.hjug.refactorfirst.plugin:refactor-first-maven-plugin:${REFACTOR_FIRST_VERSION}:htmlReport"
)

generated_report="$snapshot/target/site/refactor-first-report.html"
[[ -f $generated_report ]] || fail "RefactorFirst completed without producing $generated_report"

publish_tmp=$(mktemp "$output_dir/.refactor-first-report.XXXXXX")
cp -- "$generated_report" "$publish_tmp"
mv -f -- "$publish_tmp" "$output_file"
publish_tmp=""

printf 'RefactorFirst %s report: %s\n' "$REFACTOR_FIRST_VERSION" "$output_file"

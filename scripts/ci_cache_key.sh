#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

# Only the helper's checkout/default index supplies inputs, never inherited Git context.
git_local_names=$(git rev-parse --local-env-vars)
while IFS= read -r name; do
    unset "$name"
done <<< "$git_local_names"
export GIT_OPTIONAL_LOCKS=0
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
checkout=$(pwd -P)
top=$(git rev-parse --show-toplevel)
[[ "$top" == "$checkout" ]] || { echo 'Cache-key helper must belong to the checkout root' >&2; exit 1; }

temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
git ls-files --cached --deduplicate -z > "$temporary/tracked"
: > "$temporary/manifest"
while IFS= read -r -d '' path; do
    case "$path" in
        *.gradle|*.gradle.kts|gradle.properties|*/gradle.properties|gradle.lockfile|*/gradle.lockfile|\
        gradle/libs.versions.toml|gradle/verification-metadata.xml|\
        gradle/wrapper/gradle-wrapper.jar|gradle/wrapper/gradle-wrapper.properties|\
        Containerfile|*/Containerfile|.github/workflows/ci.yml|\
        scripts/ci_cache_key.sh|scripts/run_containerized_tests.sh) ;;
        *) continue ;;
    esac
    realpath -ze -- "$path" > "$temporary/resolved"
    IFS= read -r -d '' resolved < "$temporary/resolved"
    [[ "$resolved" == "$checkout/"* && -f "$resolved" && -r "$resolved" ]] || {
        printf 'Invalid cache-key input: %q\n' "$path" >&2
        exit 1
    }
    digest=$(sha256sum < "$resolved")
    [[ "$digest" =~ ^[0-9a-f]{64}[[:space:]]+-$ ]] || { echo 'Invalid input digest' >&2; exit 1; }
    printf '%s\0%s\0' "$path" "${digest:0:64}" >> "$temporary/manifest"
done < "$temporary/tracked"
[[ -s "$temporary/manifest" ]] || { echo 'No tracked cache-key inputs' >&2; exit 1; }
digest=$(sha256sum < "$temporary/manifest")
[[ "$digest" =~ ^[0-9a-f]{64}[[:space:]]+-$ ]] || { echo 'Invalid cache-key digest' >&2; exit 1; }
# The checkout is immutable during computation; publish nothing until every input succeeds.
printf '%s\n' "${digest:0:64}"

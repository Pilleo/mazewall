#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "usage: build_tier_e_benchmark_bundle.sh [--fingerprint <repository> <usdt>] [--write-manifest <bundle> [fingerprint]] [--verify <bundle> [fingerprint]] [--rebuild] <bundle> [usdt]" >&2
    exit 2
}

now_ms() {
    local seconds nanos
    seconds=$(date +%s)
    nanos=$(date +%N)
    [[ "${seconds}" =~ ^[0-9]+$ && "${nanos}" =~ ^[0-9]{9}$ ]] || {
        echo "date did not provide epoch seconds and nanoseconds" >&2
        return 1
    }
    printf '%s\n' "$((10#${seconds} * 1000 + 10#${nanos:0:3}))"
}

fingerprint() {
    local repository=$1 usdt=$2
    [[ "$(git -C "${repository}" rev-parse --is-inside-work-tree 2>/dev/null || true)" == "true" ]] || {
        echo "repository must be a git checkout" >&2
        return 2
    }
    [[ "${usdt}" == "true" || "${usdt}" == "false" ]] || { echo "usdt must be true or false" >&2; return 2; }

    (
        cd "${repository}"
        # `:profiler:installDist` packages these direct project dependencies. They
        # must participate in cache identity so a changed runtime class cannot be
        # benchmarked through a stale distribution bundle.
        printf 'tier-e-benchmark-bundle-v2\0usdt=%s\0native_build_type=%s\0' "${usdt}" "${MAZEWALL_TIER_E_NATIVE_BUILD_TYPE:-Release}"
        printf 'cmake=%s\0' "$(cmake --version | head -n1)"
        printf 'compiler=%s\0' "$(c++ --version | head -n1)"
        printf 'java=%s\0' "$(java -version 2>&1 | head -n1)"
        while IFS= read -r file; do
            printf 'path=%s\0' "${file}"
            sha256sum "${file}"
        done < <(
            {
                git ls-files --cached -- gradle buildSrc enforcer platform profiler profiler-native gradlew gradlew.bat build.gradle.kts settings.gradle.kts gradle.properties
                git ls-files --others --exclude-standard -- gradle buildSrc enforcer platform profiler profiler-native
            } | LC_ALL=C sort -u
        )
    ) | sha256sum | awk '{print $1}'
}

write_manifest() {
    local bundle=$1
    local input_fingerprint=${2:-}
    local fingerprint_ms=${3:-0}
    local native_configure_ms=${4:-0}
    local native_build_ms=${5:-0}
    local gradle_install_ms=${6:-0}
    local staging_ms=${7:-0}
    local total_ms=${8:-0}
    local manifest="${bundle}/manifest.json"
    local checksum_file="${bundle}/checksums.sha256"
    local artifact
    [[ -f "${bundle}/libmazewall_stack_agent.so" ]] || { echo "missing native agent" >&2; return 1; }
    [[ -d "${bundle}/runtime" ]] || { echo "missing runtime directory" >&2; return 1; }
    mapfile -t artifacts < <(cd "${bundle}" && find libmazewall_stack_agent.so runtime -type f -print | LC_ALL=C sort)
    [[ ${#artifacts[@]} -gt 1 ]] || { echo "runtime bundle has no files" >&2; return 1; }
    for artifact in "${artifacts[@]}"; do
        [[ "${artifact}" =~ ^[A-Za-z0-9._/+:-]+$ ]] || { echo "unsafe artifact name" >&2; return 1; }
    done
    (
        cd "${bundle}"
        sha256sum "${artifacts[@]}"
    ) > "${checksum_file}"
    local checksums_sha256
    checksums_sha256=$(sha256sum "${checksum_file}" | awk '{print $1}')
    {
        printf '{"schema_version":1,"input_fingerprint":"%s","artifact_count":%s,"checksums_sha256":"%s","build_timing_ms":{"fingerprint":%s,"native_configure":%s,"native_build":%s,"gradle_install":%s,"staging":%s,"total":%s}}\n' \
            "${input_fingerprint}" "${#artifacts[@]}" "${checksums_sha256}" \
            "${fingerprint_ms}" "${native_configure_ms}" "${native_build_ms}" "${gradle_install_ms}" "${staging_ms}" "${total_ms}"
    } > "${manifest}"
}

verify_manifest() {
    local bundle=$1
    local expected_input_fingerprint=${2:-}
    local manifest="${bundle}/manifest.json"
    local checksum_file="${bundle}/checksums.sha256"
    [[ -f "${manifest}" ]] || { echo "missing bundle manifest" >&2; return 1; }
    [[ "$(sed -nE 's/.*"schema_version":([0-9]+).*/\1/p' "${manifest}")" == "1" ]] || { echo "unsupported bundle manifest" >&2; return 1; }
    local input_fingerprint checksums_sha256 declared_count
    input_fingerprint=$(sed -nE 's/.*"input_fingerprint":"([a-f0-9]*)".*/\1/p' "${manifest}")
    [[ -z "${input_fingerprint}" || "${input_fingerprint}" =~ ^[a-f0-9]{64}$ ]] || { echo "invalid input fingerprint" >&2; return 1; }
    if [[ -n "${expected_input_fingerprint}" ]]; then
        [[ "${expected_input_fingerprint}" =~ ^[a-f0-9]{64}$ ]] || { echo "invalid expected input fingerprint" >&2; return 1; }
        [[ "${input_fingerprint}" == "${expected_input_fingerprint}" ]] || { echo "bundle input fingerprint mismatch" >&2; return 1; }
    fi
    checksums_sha256=$(sed -nE 's/.*"checksums_sha256":"([a-f0-9]{64})".*/\1/p' "${manifest}")
    declared_count=$(sed -nE 's/.*"artifact_count":([0-9]+).*/\1/p' "${manifest}")
    [[ "${checksums_sha256}" =~ ^[a-f0-9]{64}$ && "${declared_count}" =~ ^[1-9][0-9]*$ ]] || { echo "invalid bundle manifest fields" >&2; return 1; }
    [[ -f "${checksum_file}" ]] || { echo "missing bundle checksums" >&2; return 1; }
    [[ "$(sha256sum "${checksum_file}" | awk '{print $1}')" == "${checksums_sha256}" ]] || { echo "bundle checksum list mismatch" >&2; return 1; }
    local count=0 path expected actual separator
    while read -r expected path; do
        [[ -n "${path}" && "${path}" != /* && "${path}" != *".."* ]] || { echo "unsafe artifact path" >&2; return 1; }
        [[ "${expected}" =~ ^[a-f0-9]{64}$ ]] || { echo "invalid artifact checksum" >&2; return 1; }
        [[ -f "${bundle}/${path}" ]] || { echo "missing artifact ${path}" >&2; return 1; }
        actual=$(sha256sum "${bundle}/${path}" | awk '{print $1}')
        [[ "${actual}" == "${expected}" ]] || { echo "artifact checksum mismatch: ${path}" >&2; return 1; }
        count=$((count + 1))
    done < "${checksum_file}"
    [[ ${count} -eq ${declared_count} && ${count} -gt 1 ]] || { echo "bundle manifest has insufficient artifacts" >&2; return 1; }
}

if [[ ${1:-} == "--fingerprint" ]]; then
    [[ $# -eq 3 ]] || usage
    fingerprint "$2" "$3"
    exit 0
fi
if [[ ${1:-} == "--write-manifest" ]]; then
    [[ $# -eq 2 || $# -eq 3 ]] || usage
    write_manifest "$2" "${3:-}"
    exit 0
fi
if [[ ${1:-} == "--verify" ]]; then
    [[ $# -eq 2 || $# -eq 3 ]] || usage
    verify_manifest "$2" "${3:-}"
    exit 0
fi

force_rebuild=false
if [[ ${1:-} == "--rebuild" ]]; then
    force_rebuild=true
    shift
fi
[[ $# -eq 1 || $# -eq 2 ]] || usage

bundle=$1
usdt=${2:-false}
repository=${MAZEWALL_TIER_E_REPOSITORY_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
cache_root=${MAZEWALL_TIER_E_BUILD_CACHE_ROOT:-"${repository}/build/tier-e-benchmark-cache"}
native_build_type=${MAZEWALL_TIER_E_NATIVE_BUILD_TYPE:-Release}
[[ "${native_build_type}" =~ ^[A-Za-z0-9_]+$ ]] || { echo "invalid native build type" >&2; exit 2; }
started_ms=$(now_ms)
bundle_fingerprint=$(fingerprint "${repository}" "${usdt}")
fingerprint_ms=$(( $(now_ms) - started_ms ))

if [[ -e "${bundle}/manifest.json" ]]; then
    verify_manifest "${bundle}"
    if [[ "${force_rebuild}" == "false" ]] && verify_manifest "${bundle}" "${bundle_fingerprint}"; then
        printf 'tier-e-benchmark-bundle: status=reused fingerprint=%s bundle=%s elapsed_ms=%s\n' \
            "${bundle_fingerprint}" "${bundle}" "$(( $(now_ms) - started_ms ))"
        exit 0
    fi
fi
if [[ -e "${bundle}" && ! -e "${bundle}/manifest.json" ]]; then
    echo "refusing to replace a bundle without a valid manifest: ${bundle}" >&2
    exit 1
fi

mkdir -p "${cache_root}"
exec 9>"${cache_root}/bundle-build.lock"
flock 9
native_build="${cache_root}/native-${bundle_fingerprint}"
runtime_cache="${cache_root}/runtime-${bundle_fingerprint}"
gradle_home="${cache_root}/gradle-home"
mkdir -p "${gradle_home}" "${native_build}"

phase_started_ms=$(now_ms)
cmake -S "${repository}/profiler-native" -B "${native_build}" -DCMAKE_BUILD_TYPE="${native_build_type}" -DMAZEWALL_ENABLE_TIER_E_USDT="${usdt}"
native_configure_ms=$(( $(now_ms) - phase_started_ms ))
phase_started_ms=$(now_ms)
cmake --build "${native_build}" -j2
native_build_ms=$(( $(now_ms) - phase_started_ms ))
phase_started_ms=$(now_ms)
if [[ -e "${runtime_cache}" && ! -f "${runtime_cache}/input-fingerprint" ]]; then
    echo "runtime cache is incomplete: ${runtime_cache}" >&2
    exit 1
fi
if [[ ! -f "${runtime_cache}/input-fingerprint" ]]; then
    runtime_stage=$(mktemp -d "${cache_root}/.tier-e-runtime.XXXXXX")
    if ! GRADLE_USER_HOME="${gradle_home}" "${repository}/gradlew" --no-daemon :profiler:installDist; then
        rm -rf "${runtime_stage}"
        exit 1
    fi
    mkdir -p "${runtime_stage}/runtime"
    cp -a "${repository}/profiler/build/install/tier-e-daemon/lib/." "${runtime_stage}/runtime/"
    printf '%s\n' "${bundle_fingerprint}" > "${runtime_stage}/input-fingerprint"
    mv "${runtime_stage}" "${runtime_cache}"
fi
[[ "$(cat "${runtime_cache}/input-fingerprint")" == "${bundle_fingerprint}" ]] || {
    echo "runtime cache fingerprint mismatch" >&2
    exit 1
}
[[ -d "${runtime_cache}/runtime" ]] || { echo "runtime cache is incomplete" >&2; exit 1; }
gradle_install_ms=$(( $(now_ms) - phase_started_ms ))

phase_started_ms=$(now_ms)
bundle_parent=$(dirname "${bundle}")
mkdir -p "${bundle_parent}"
stage=$(mktemp -d "${bundle_parent}/.tier-e-bundle.XXXXXX")
trap 'rm -rf "${stage}"' EXIT
mkdir -p "${stage}/runtime"
cp "${native_build}/libmazewall_stack_agent.so" "${stage}/libmazewall_stack_agent.so"
cp -a "${runtime_cache}/runtime/." "${stage}/runtime/"
write_manifest "${stage}" "${bundle_fingerprint}" "${fingerprint_ms}" "${native_configure_ms}" "${native_build_ms}" "${gradle_install_ms}" "$(( $(now_ms) - phase_started_ms ))" "$(( $(now_ms) - started_ms ))"
verify_manifest "${stage}"
if [[ -e "${bundle}" ]]; then
    prior_fingerprint=$(sed -nE 's/.*"input_fingerprint":"([a-f0-9]*)".*/\1/p' "${bundle}/manifest.json")
    replacement="${bundle}.replaced-${prior_fingerprint:-unbound}"
    [[ ! -e "${replacement}" ]] || { echo "refusing to overwrite prior bundle ${replacement}" >&2; exit 1; }
    mv "${bundle}" "${replacement}"
fi
mv "${stage}" "${bundle}"
trap - EXIT
printf 'tier-e-benchmark-bundle: status=built fingerprint=%s bundle=%s\n' "${bundle_fingerprint}" "${bundle}"

#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="${1:-$root/enforcer/src/test/c/libvulnerable_rop.c}"

# This is a local compilation input, never cached kernel/protection evidence.
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    printf 'unsupported:%s:%s\n' "$(uname -s)" "$(uname -m)"
    exit 0
fi
if ! command -v gcc >/dev/null 2>&1; then
    printf 'compiler-unavailable\n'
    exit 0
fi

hash_program() {
    local program="$1"
    if [[ ! -f "$program" ]]; then program="$(command -v "$program")"; fi
    sha256sum "$(readlink -f "$program")"
}

{
    uname -s
    uname -m
    gcc --version
    gcc -dumpmachine
    gcc -dumpspecs
    hash_program "$(command -v gcc)"
    hash_program "$(gcc -print-prog-name=cc1)"
    hash_program "$(gcc -print-prog-name=ld)"
    for library in crtbeginS.o crtendS.o crti.o crtn.o libgcc_s.so libc.so; do
        selected="$(gcc -print-file-name="$library")"
        printf 'link-input=%s\n' "$selected"
        if [[ -f "$selected" ]]; then sha256sum "$(readlink -f "$selected")"; fi
    done
    # Preprocessing includes the effective system/user headers and compiler defaults.
    gcc -E -fPIC -fno-stack-protector "$source_file"
    for name in GCC_EXEC_PREFIX COMPILER_PATH CPATH C_INCLUDE_PATH LIBRARY_PATH LD_LIBRARY_PATH SOURCE_DATE_EPOCH; do
        printf '%s=%s\n' "$name" "${!name-}"
    done
} | sha256sum | cut -d ' ' -f 1

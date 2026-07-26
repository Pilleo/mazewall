#!/usr/bin/env bash
set -e

# Ensure we are in the project root directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
PROJECT_ROOT="$DIR/.."
cd "$PROJECT_ROOT"

# Check if we are running on Linux x86_64
if [ "$(uname)" != "Linux" ] || [ "$(uname -m)" != "x86_64" ]; then
    echo "Warning: Native CET compilation is only supported on Linux x86_64. Skipping compilation."
    exit 0
fi

# Check if gcc is available
if ! command -v gcc &> /dev/null; then
    echo "Warning: gcc is not installed. Skipping native CET compilation."
    exit 0
fi

# Ensure the C source file exists
C_SRC="enforcer/src/test/c/libvulnerable_rop.c"
if [ ! -f "$C_SRC" ]; then
    echo "Error: C source file $C_SRC not found." >&2
    exit 1
fi

# Compile the vulnerable C library
echo "Compiling vulnerable C library without stack canary..."
gcc -shared -fPIC -fno-stack-protector -o libvulnerable_rop.so "$C_SRC"
echo "libvulnerable_rop.so compiled successfully."

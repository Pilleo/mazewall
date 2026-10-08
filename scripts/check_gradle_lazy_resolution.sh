#!/usr/bin/env bash
set -euo pipefail

# Resolve the implementation relative to this script; scan the caller's checkout.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$script_dir/gradle_lazy_resolution.py"

#!/usr/bin/env bash
# Tier E FFM boundary gate (R1 refactoring guard).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src/main/kotlin"
violations=0
while IFS= read -r f; do
    rel="${f#$SRC/}"
    case "$rel" in io/mazewall/tierE/ffi/*|io/mazewall/tierE/shim/*) continue ;; esac
    if grep -qnE '\.invoke\(|MethodHandle|SymbolLookup|Arena\.of' "$f"; then
        echo "VIOLATION: $rel"
        violations=$((violations+1))
    fi
done < <(find "$SRC" -name '*.kt' | sort)
if (( violations > 0 )); then echo "FFM boundary gate: $violations violations" >&2; exit 1; fi
echo "FFM boundary gate: clean"

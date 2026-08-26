#!/usr/bin/env bash
# Tier E FFM boundary gate (R1 refactoring guard).
#
# Raw MethodHandle.invoke / heap-segment downcall mistakes are RUNTIME
# failures invisible to kotlinc. This gate keeps every raw FFM call confined
# to the ffi/shim packages where the typed wrappers live.
#
# Usage: check-boundaries.sh  (exit 1 on violation)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/src/main/kotlin"

violations=0
while IFS= read -r file; do
    rel="${file#"$SRC"/}"
    case "$rel" in
        io/mazewall/tierE/ffi/*|io/mazewall/tierE/shim/*) continue ;;
    esac
    if grep -nE "\.invoke\(|MethodHandle|SymbolLookup|Arena\.of" "$file" >/dev/null; then
        echo "VIOLATION: raw FFM outside ffi/shim: $rel"
        grep -nE "\.invoke\(|MethodHandle|SymbolLookup|Arena\.of" "$file" | sed 's/^/    /'
        violations=$((violations+1))
    fi
done < <(find "$SRC" -name '*.kt' | sort)

if (( violations > 0 )); then
    echo "FFM boundary gate: $violations violating file(s)" >&2
    exit 1
fi
echo "FFM boundary gate: clean"

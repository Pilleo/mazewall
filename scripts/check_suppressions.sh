#!/usr/bin/env bash
# Audits production Kotlin suppressions independently of Detekt so CI catches annotation debt.
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
allowlist="$repo_root/config/detekt/approved-suppressions.txt"
failed=0
site_count=0

while IFS=: read -r file line _; do
    relative_file=${file#"$repo_root/"}
    annotation=$(sed -n "${line},$((line + 8))p" "$file" | awk '
        NR == 1 && /@Suppress\([^)]*\)/ { print; exit }
        { print }
        /^[[:space:]]*\)[[:space:]]*(\/\/.*)?$/ { exit }
    ')
    rationale_line=$((line - 1))
    rationale=$(sed -n "${rationale_line}p" "$file")

    while IFS= read -r rule; do
        [ -n "$rule" ] || continue
        site_count=$((site_count + 1))
        entry="${relative_file}:${line}:${rule}"
        if ! grep -Fqx "$entry" "$allowlist" || [[ "$rationale" != *"SUPPRESSION-RATIONALE:"* ]]; then
            printf 'Unapproved production suppression: %s\n' "$entry" >&2
            printf '  Add a reviewed allowlist entry and an adjacent SUPPRESSION-RATIONALE comment, or remove it.\n' >&2
            failed=1
        fi
    done < <(printf '%s\n' "$annotation" | grep -oE '"[A-Za-z][A-Za-z0-9]*"' | tr -d '"')
done < <(
    rg -n --glob '*/src/main/**/*.kt' --glob '!**/build/**' '@Suppress\(' "$repo_root" || true
)

if [ "$failed" -ne 0 ]; then
    exit 1
fi

printf 'Suppression audit passed: %d production rule entries are reviewed.\n' "$site_count"

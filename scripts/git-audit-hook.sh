#!/usr/bin/env bash
# Git pre-commit hook to audit changes against mazewall security boundaries.
# This prevents agent loops or rogue code modifications from bypassing seccomp/Landlock rules.

set -euo pipefail

echo "==> Auditing codebase changes for security exceptions and bypasses..."

# 1. Check for unauthorized seccomp/bypass modifications in source files
FORBIDDEN_PATTERNS=(
  "FallbackBehavior.WARN_AND_BYPASS"
  "seccomp_filter_flag"
  "sun.misc.Unsafe"
)

CHANGED_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.(kt|java)$' || true)

if [ -n "$CHANGED_FILES" ]; then
  for file in $CHANGED_FILES; do
    for pattern in "${FORBIDDEN_PATTERNS[@]}"; do
      if git diff --cached "$file" | grep -E "^\+\s*.*$pattern" >/dev/null 2>&1; then
        echo "ERROR: Security violation in $file. Detected disallowed pattern: '$pattern'"
        echo "Agents are not permitted to modify core fallback behaviors or use raw Unsafe memory directly."
        exit 1
      fi
    done
  done
fi

# 2. Check for modifications to the core filter engine
CORE_FILES=$(git diff --cached --name-only | grep -E 'BpfFilter\.kt|LinuxNative\.kt' || true)
if [ -n "$CORE_FILES" ]; then
  echo "WARNING: Modifications to core security engine files detected: $CORE_FILES"
  echo "Please ensure these changes are fully validated through './scripts/run_tests.sh'."
fi

echo "==> Security audit passed successfully."
exit 0

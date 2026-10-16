#!/usr/bin/env bash
# Git pre-commit audit hook wrapper
# Delegates to version-controlled scripts/hooks/pre-commit

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)"
exec "$SCRIPT_DIR/hooks/pre-commit" "$@"

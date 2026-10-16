#!/usr/bin/env bash
# Repository-local wrapper for ast-grep to handle system path/installation issues.
# Safe for use in environments where ast-grep may or may not be installed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source central tool finder if present
if [ -f "$SCRIPT_DIR/find-tool.sh" ]; then
    # shellcheck source=scripts/find-tool.sh
    source "$SCRIPT_DIR/find-tool.sh"
    AST_GREP_BIN="$(find_ast_grep 2>/dev/null || true)"
else
    # Fallback inline resolution
    AST_GREP_BIN="$(command -v ast-grep || command -v sg || echo "$HOME/node_modules/.bin/ast-grep")"
fi

# Query availability check
if [ "${1:-}" = "--is-available" ] || [ "${1:-}" = "--check-available" ]; then
    if [ -n "$AST_GREP_BIN" ] && [ -x "$AST_GREP_BIN" ]; then
        exit 0
    else
        exit 1
    fi
fi

if [ -z "$AST_GREP_BIN" ] || [ ! -x "$AST_GREP_BIN" ]; then
    if [ "${AST_GREP_OPTIONAL:-0}" = "1" ]; then
        echo "Notice: ast-grep binary not found; skipping optional AST check." >&2
        exit 0
    fi
    echo "Error: ast-grep binary not found in PATH, mise, or ~/.node_modules/.bin." >&2
    echo "To install: npm install -g @ast-grep/cli  (or: cargo install ast-grep --locked)" >&2
    exit 1
fi

exec "$AST_GREP_BIN" "$@"

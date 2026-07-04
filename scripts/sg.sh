#!/usr/bin/env bash
# Repository-local wrapper for ast-grep to handle system path/installation issues

# Try to find the binary dynamically (prefer system PATH first)
if command -v ast-grep &>/dev/null; then
    AST_GREP_BIN="ast-grep"
elif command -v sg &>/dev/null; then
    AST_GREP_BIN="sg"
elif [ -f "$HOME/node_modules/.bin/ast-grep" ]; then
    AST_GREP_BIN="$HOME/node_modules/.bin/ast-grep"
else
    echo "Error: ast-grep binary not found in PATH or \$HOME/node_modules/.bin/ast-grep."
    echo "Please install it via: npm install -g @ast-grep/cli"
    exit 1
fi

exec "$AST_GREP_BIN" "$@"

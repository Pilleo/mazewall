#!/usr/bin/env bash
# Helper script to locate optional developer tools (codanna, jbcontext, ast-grep/sg)
# Safe for use in git hooks and scripts across diverse developer environments and CI.

find_executable() {
    local cmd_name="$1"
    shift
    # 1. Check current PATH
    if command -v "$cmd_name" >/dev/null 2>&1; then
        command -v "$cmd_name"
        return 0
    fi
    # 2. Check candidate paths (supports wildcards/globs)
    for pattern in "$@"; do
        for candidate in $pattern; do
            if [ -x "$candidate" ]; then
                echo "$candidate"
                return 0
            fi
        done
    done
    return 1
}

find_codanna() {
    if [ -n "${CODANNA_BIN:-}" ] && [ -x "$CODANNA_BIN" ]; then
        echo "$CODANNA_BIN"
        return 0
    fi
    find_executable codanna \
        "$HOME/.local/bin/codanna" \
        "$HOME/.cargo/bin/codanna" \
        "/usr/local/bin/codanna" \
        "/opt/homebrew/bin/codanna"
}

find_jbcontext() {
    if [ -n "${JBCONTEXT_BIN:-}" ] && [ -x "$JBCONTEXT_BIN" ]; then
        echo "$JBCONTEXT_BIN"
        return 0
    fi
    if [ -n "${JBCONTEXT:-}" ] && [ -x "$JBCONTEXT" ]; then
        echo "$JBCONTEXT"
        return 0
    fi
    find_executable jbcontext \
        "$HOME/.jbcontext/bin/jbcontext" \
        "$HOME/.local/bin/jbcontext" \
        "/usr/local/bin/jbcontext" \
        "/opt/homebrew/bin/jbcontext"
}

find_ast_grep() {
    if [ -n "${AST_GREP_BIN:-}" ] && [ -x "$AST_GREP_BIN" ]; then
        echo "$AST_GREP_BIN"
        return 0
    fi
    # Try ast-grep first, then sg
    find_executable ast-grep \
        "$HOME/.local/share/mise/installs/node/*/bin/ast-grep" \
        "$HOME/.nvm/versions/node/*/bin/ast-grep" \
        "$HOME/.local/share/pnpm/ast-grep" \
        "$HOME/.npm-global/bin/ast-grep" \
        "$HOME/.cargo/bin/ast-grep" \
        "$HOME/.local/bin/ast-grep" \
        "$HOME/node_modules/.bin/ast-grep" \
        "/usr/local/bin/ast-grep" \
        "/opt/homebrew/bin/ast-grep" \
    || find_executable sg \
        "$HOME/.local/share/mise/installs/node/*/bin/sg" \
        "$HOME/.nvm/versions/node/*/bin/sg" \
        "$HOME/.cargo/bin/sg" \
        "$HOME/.local/bin/sg" \
        "/usr/local/bin/sg" \
        "/opt/homebrew/bin/sg"
}

# If executed directly (not sourced)
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    target="${1:-status}"
    case "$target" in
        codanna)
            find_codanna || { echo "codanna: not found" >&2; exit 1; }
            ;;
        jbcontext)
            find_jbcontext || { echo "jbcontext: not found" >&2; exit 1; }
            ;;
        ast-grep|sg)
            find_ast_grep || { echo "ast-grep: not found" >&2; exit 1; }
            ;;
        --status|status)
            echo "Optional Developer Tools Status:"
            if p=$(find_codanna 2>/dev/null); then
                echo "  [✓] codanna:   $p"
            else
                echo "  [ ] codanna:   not available (hooks will safely skip)"
            fi
            if p=$(find_jbcontext 2>/dev/null); then
                echo "  [✓] jbcontext: $p"
            else
                echo "  [ ] jbcontext: not available (hooks will safely skip)"
            fi
            if p=$(find_ast_grep 2>/dev/null); then
                echo "  [✓] ast-grep:  $p"
            else
                echo "  [ ] ast-grep:  not available (hooks will safely skip)"
            fi
            ;;
        *)
            echo "Usage: $0 [codanna|jbcontext|ast-grep|--status]" >&2
            exit 2
            ;;
    esac
fi

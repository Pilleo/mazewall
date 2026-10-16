#!/usr/bin/env bash
# Quick code intelligence query wrapper for Codanna MCP
# Usage: ./scripts/code_atlas.sh callers BpfFilter
#        ./scripts/code_atlas.sh search "seccomp linear scan"
#        ./scripts/code_atlas.sh work-package PolicyCompilationCache

COMMAND="${1:-}"
SYMBOL="${2:-}"

if [ -z "$COMMAND" ]; then
    echo "Usage: $0 {callers|calls|describe|search|impact|work-package|blast-radius|reindex} <symbol_or_query>"
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAST_INDEX_FILE="$ROOT_DIR/.codanna/.last_indexed"

# Resolve codanna binary safely
CODANNA_BIN=""
if [ -f "$ROOT_DIR/scripts/find-tool.sh" ]; then
    # shellcheck source=scripts/find-tool.sh
    source "$ROOT_DIR/scripts/find-tool.sh"
    CODANNA_BIN="$(find_codanna 2>/dev/null || true)"
fi
if [ -z "$CODANNA_BIN" ]; then
    CODANNA_BIN="$(command -v codanna || echo "$HOME/.local/bin/codanna")"
fi

ensure_fresh_index() {
    if [ ! -x "$CODANNA_BIN" ]; then
        return
    fi
    local dirty=0
    if [ ! -f "$LAST_INDEX_FILE" ] || [ ! -d "$ROOT_DIR/.codanna/index" ]; then
        dirty=1
    else
        # Check if any .kt, .java, or .kts source file is newer than the last index timestamp
        if find "$ROOT_DIR" \( -name "build" -o -name "target" -o -name ".git" -o -name ".gradle" -o -name ".codanna" \) -prune -o \
            \( -name "*.kt" -o -name "*.java" -o -name "*.kts" \) -newer "$LAST_INDEX_FILE" -print -quit 2>/dev/null | grep -q .; then
            dirty=1
        fi
    fi

    if [ "$dirty" -eq 1 ]; then
        mkdir -p "$ROOT_DIR/.codanna"
        "$CODANNA_BIN" index --force --no-progress >/dev/null 2>&1 || true
        touch "$LAST_INDEX_FILE"
    fi
}

require_codanna() {
    if [ -z "$CODANNA_BIN" ] || [ ! -x "$CODANNA_BIN" ]; then
        echo "Error: codanna binary not found. Please install codanna or check PATH." >&2
        exit 1
    fi
}

case "$COMMAND" in
    callers)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        OUTPUT="$("$CODANNA_BIN" mcp find_callers "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] && echo "$OUTPUT" | grep -q "Ambiguous"; then
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'io\.mazewall\.[^ ]*' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                echo "==> Ambiguous symbol. Defaulting to: $FIRST_ID"
                "$CODANNA_BIN" mcp find_callers "$FIRST_ID"
                exit $?
            fi
        fi
        echo "$OUTPUT"
        exit $STATUS
        ;;
    calls)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        OUTPUT="$("$CODANNA_BIN" mcp get_calls "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] && echo "$OUTPUT" | grep -q "Ambiguous"; then
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'io\.mazewall\.[^ ]*' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                echo "==> Ambiguous symbol. Defaulting to: $FIRST_ID"
                "$CODANNA_BIN" mcp get_calls "$FIRST_ID"
                exit $?
            fi
        fi
        echo "$OUTPUT"
        exit $STATUS
        ;;
    describe)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        OUTPUT="$("$CODANNA_BIN" retrieve describe "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] && echo "$OUTPUT" | grep -q "Ambiguous"; then
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'io\.mazewall\.[^ ]*' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                echo "==> Ambiguous symbol. Defaulting to: $FIRST_ID"
                "$CODANNA_BIN" retrieve describe "$FIRST_ID"
                exit $?
            fi
        fi
        echo "$OUTPUT"
        exit $STATUS
        ;;
    search)
        if [ -z "$SYMBOL" ]; then echo "Error: Query required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        "$CODANNA_BIN" mcp semantic_search_docs query:"$SYMBOL"
        ;;
    impact)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        "$CODANNA_BIN" mcp analyze_impact "$SYMBOL"
        ;;
    work-package)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol or file required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        shift
        ARGS_FILE="$(mktemp)"
        cleanup() { rm -f "$ARGS_FILE"; }
        trap cleanup EXIT
        for arg in "$@"; do
            printf '%s\n' "$arg" >> "$ARGS_FILE"
        done
        exec ./gradlew -q :tools:orchestrator:workPackage -PincludeOrchestrator=true -PworkPackageArgsFile="$ARGS_FILE"
        ;;
    blast-radius)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol required"; exit 1; fi
        require_codanna
        ensure_fresh_index
        ARGS_FILE="$(mktemp)"
        cleanup() { rm -f "$ARGS_FILE"; }
        trap cleanup EXIT
        shift
        for arg in "$SYMBOL" "$@"; do
            printf '%s\n' "$arg" >> "$ARGS_FILE"
        done
        exec ./gradlew -q :tools:orchestrator:blastRadiusMemory -PincludeOrchestrator=true -PblastRadiusArgsFile="$ARGS_FILE"
        ;;
    reindex)
        require_codanna
        echo "Rebuilding Codanna index from workspace..."
        mkdir -p "$ROOT_DIR/.codanna"
        "$CODANNA_BIN" index --force --no-progress
        touch "$LAST_INDEX_FILE"
        ;;
    *)
        echo "Unknown command: $COMMAND"
        echo "Supported commands: callers, calls, describe, search, impact, work-package, blast-radius, reindex"
        exit 1
        ;;
esac

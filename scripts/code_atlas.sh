#!/usr/bin/env bash
# Quick code intelligence query wrapper for Codanna MCP
# Usage: ./scripts/code_atlas.sh callers BpfFilter
#        ./scripts/code_atlas.sh search "seccomp linear scan"
#        ./scripts/code_atlas.sh work-package PolicyCompilationCache

COMMAND="$1"
SYMBOL="$2"

if [ -z "$COMMAND" ]; then
    echo "Usage: $0 {callers|calls|describe|search|impact|work-package} <symbol_or_query>"
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAST_INDEX_FILE="$ROOT_DIR/.codanna/.last_indexed"

ensure_fresh_index() {
    if ! command -v codanna >/dev/null 2>&1; then
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
        codanna index --force --no-progress >/dev/null 2>&1 || true
        touch "$LAST_INDEX_FILE"
    fi
}

case "$COMMAND" in
    callers)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        ensure_fresh_index
        OUTPUT="$(codanna mcp find_callers "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] || echo "$OUTPUT" | grep -q "Ambiguous: found.*named"; then
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'symbol_id:[0-9]\+' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                codanna mcp find_callers "$FIRST_ID"
            else
                echo "$OUTPUT"
                exit $STATUS
            fi
        else
            echo "$OUTPUT"
            exit $STATUS
        fi
        ;;
    calls)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        ensure_fresh_index
        OUTPUT="$(codanna mcp get_calls "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] || echo "$OUTPUT" | grep -q "Ambiguous: found.*named"; then
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'symbol_id:[0-9]\+' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                codanna mcp get_calls "$FIRST_ID"
            else
                echo "$OUTPUT"
                exit $STATUS
            fi
        else
            echo "$OUTPUT"
            exit $STATUS
        fi
        ;;
    describe)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        ensure_fresh_index
        # Attempt direct describe
        OUTPUT="$(codanna retrieve describe "$SYMBOL" 2>&1)"
        STATUS=$?
        if [ $STATUS -ne 0 ] && echo "$OUTPUT" | grep -q "Ambiguous: found.*named"; then
            # Extract the first symbol_id from the suggestions / output
            FIRST_ID="$(echo "$OUTPUT" | grep -o 'symbol_id:[0-9]\+' | head -n 1)"
            if [ -n "$FIRST_ID" ]; then
                codanna retrieve describe "$FIRST_ID"
            else
                echo "$OUTPUT"
                exit $STATUS
            fi
        else
            echo "$OUTPUT"
            exit $STATUS
        fi
        ;;
    search)
        if [ -z "$SYMBOL" ]; then echo "Error: Search query required"; exit 1; fi
        ensure_fresh_index
        codanna mcp semantic_search_docs query:"$SYMBOL"
        ;;
    impact)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        ensure_fresh_index
        codanna mcp analyze_impact "$SYMBOL"
        ;;
    work-package)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol or file required"; exit 1; fi
        if ! command -v codanna >/dev/null 2>&1; then
            echo "codanna not found" >&2
            exit 1
        fi
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
    reindex)
        echo "Rebuilding Codanna index from workspace..."
        mkdir -p "$ROOT_DIR/.codanna"
        codanna index --force --no-progress
        touch "$LAST_INDEX_FILE"
        ;;
    *)
        echo "Unknown command: $COMMAND"
        echo "Supported commands: callers, calls, describe, search, impact, work-package, reindex"
        exit 1
        ;;
esac

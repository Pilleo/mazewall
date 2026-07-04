#!/usr/bin/env bash
# Quick code intelligence query wrapper for Codanna MCP
# Usage: ./scripts/code_atlas.sh callers BpfFilter
#        ./scripts/code_atlas.sh search "seccomp linear scan"

COMMAND="$1"
SYMBOL="$2"

if [ -z "$COMMAND" ]; then
    echo "Usage: $0 {callers|calls|describe|search|impact} <symbol_or_query>"
    exit 1
fi

case "$COMMAND" in
    callers)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        codanna mcp find_callers "$SYMBOL"
        ;;
    calls)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        codanna mcp get_calls "$SYMBOL"
        ;;
    describe)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        codanna retrieve describe "$SYMBOL"
        ;;
    search)
        if [ -z "$SYMBOL" ]; then echo "Error: Search query required"; exit 1; fi
        codanna mcp semantic_search_docs query:"$SYMBOL"
        ;;
    impact)
        if [ -z "$SYMBOL" ]; then echo "Error: Symbol name required"; exit 1; fi
        codanna mcp analyze_impact "$SYMBOL"
        ;;
    *)
        echo "Unknown command: $COMMAND"
        echo "Supported commands: callers, calls, describe, search, impact"
        exit 1
        ;;
esac

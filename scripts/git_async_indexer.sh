#!/usr/bin/env bash
# Universal asynchronous indexer for incoming commits & branch switches.
# Keeps Codanna code atlas and agentmemory in sync with Jules / background workers.

set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if command -v codanna >/dev/null 2>&1; then
    (
        cd "$ROOT_DIR"
        codanna index --no-progress >/dev/null 2>&1 || true
        touch "$ROOT_DIR/.codanna/.last_indexed" 2>/dev/null || true
    ) &
fi

LAST_COMMIT_MSG="$(git log -1 --pretty=%B 2>/dev/null | head -n 1 || true)"
if [ -n "$LAST_COMMIT_MSG" ] && [ "$LAST_COMMIT_MSG" != "WIP" ]; then
    python3 -c "
import json, os, time

msg = '''$LAST_COMMIT_MSG'''.strip()
root = '$ROOT_DIR'
mem_path = os.path.expanduser('~/.agentmemory/standalone.json')
if os.path.exists(mem_path) and len(msg) > 10:
    try:
        with open(mem_path, 'r') as f:
            d = json.load(f)
        ts = int(time.time())
        mem_id = f'mem_commit_{ts}'
        d.setdefault('mem:memories', {})[mem_id] = {
            'id': mem_id,
            'type': 'fact',
            'title': f'Git Commit: {msg[:60]}',
            'content': f'Incoming commit landed in {root}: {msg}',
            'concepts': [],
            'files': [],
            'createdAt': '2026-09-05T01:14:00.000Z',
            'updatedAt': '2026-09-05T01:14:00.000Z',
            'strength': 6,
            'version': 1,
            'isLatest': True,
            'sessionIds': []
        }
        with open(mem_path, 'w') as f:
            json.dump(d, f, indent=2)
    except Exception as e:
        pass
" 2>/dev/null || true
fi

# Sync any newly added/resolved backlog issues into negative error memory
if [ -f "/scripts/backlog_negative_indexer.py" ]; then
    python3 "/scripts/backlog_negative_indexer.py" >/dev/null 2>&1 &
fi

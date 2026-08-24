#!/usr/bin/env bash
# scripts/run_paperclip_loop.sh
#
# One-command Paperclip hybrid loop tick:
#   1. Ingest open backlog issues into Paperclip (topological, idempotent).
#   2. Dispatch the next dispatchable board issue to an agent (default: Jules,
#      matching the orchestrator's worker) via assign + status -> in_progress
#      (the status transition is what triggers the run).
#
# Resolution/review/approvals are handled by the board's native reviewer flow
# plus scripts/paperclip_telegram_bridge.py (run it separately as a daemon).
#
# Environment:
#   PAPERCLIP_API_KEY        API key                  (default: local)
#   PAPERCLIP_API_URL        Board base URL           (default: http://127.0.0.1:3100)
#   PAPERCLIP_COMPANY_ID     Company id               (default: auto-detect)
#   PAPERCLIP_AGENT_ADAPTER  adapterType to dispatch  (default: jules)
#   PAPERCLIP_MAX_DISPATCH   max issues per tick      (default: 1)
#
# Usage:
#   ./scripts/run_paperclip_loop.sh [--dry-run]

set -euo pipefail

API_URL="${PAPERCLIP_API_URL:-http://127.0.0.1:3100}"
API_KEY="${PAPERCLIP_API_KEY:-local}"
ADAPTER="${PAPERCLIP_AGENT_ADAPTER:-jules}"
MAX_DISPATCH="${PAPERCLIP_MAX_DISPATCH:-1}"

api() {
    curl -sS -m 15 -H "Authorization: Bearer ${API_KEY}" "$@"
}

DRY_RUN=false
for arg in "$@"; do
    case "${arg}" in
        --dry-run|-n) DRY_RUN=true ;;
    esac
done

echo "==> [1/3] Ingesting backlog DAG into Paperclip"
PAPERCLIP_API_URL="${API_URL}" PAPERCLIP_API_KEY="${API_KEY}" \
    kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts "$@"

if [ "${DRY_RUN}" == "true" ]; then
    echo "==> Dry-run: skipping dispatch phase."
    exit 0
fi

COMPANY_ID="${PAPERCLIP_COMPANY_ID:-$(api "${API_URL}/api/companies" | jq -r '.[0].id')}"
if [ -z "${COMPANY_ID}" ] || [ "${COMPANY_ID}" == "null" ]; then
    echo "ERROR: could not resolve company id" >&2
    exit 1
fi

AGENT_ID="$(api "${API_URL}/api/companies/${COMPANY_ID}/agents" |
    jq -r --arg a "${ADAPTER}" '[.[] | select(.adapterType == $a)][0].id? // empty')"
if [ -z "${AGENT_ID}" ]; then
    echo "ERROR: no idle-registered agent with adapterType '${ADAPTER}' on the roster." >&2
    exit 1
fi
echo "==> [2/3] Dispatch target: adapter=${ADAPTER} agent=${AGENT_ID}"

echo "==> [3/3] Selecting dispatchable issues"
DISPATCHED=0
while [ "${DISPATCHED}" -lt "${MAX_DISPATCH}" ]; do
    CANDIDATE=$(api "${API_URL}/api/companies/${COMPANY_ID}/issues" | python3 -c '
import json, sys

rank = {"high": 2, "medium": 1, "low": 0}
issues = json.load(sys.stdin)
candidates = [
    i for i in issues
    # Only dispatch issues that were ingested from the markdown backlog
    # (linkage marker) — never manual or foreign board entries.
    if i.get("status") == "backlog"
    and "mazewall:backlog-file=" in (i.get("description") or "")
    and not i.get("assigneeAgentId")
    and all(b.get("status") in ("done", "cancelled") for b in i.get("blockedBy", []))
]
candidates.sort(key=lambda i: (-rank.get(i.get("priority", "low"), 0), i["issueNumber"]))
print(candidates[0]["id"] if candidates else "")
')
    if [ -z "${CANDIDATE}" ]; then
        echo "    No unassigned, unblocked 'backlog' issues left."
        break
    fi

    api -X PATCH "${API_URL}/api/issues/${CANDIDATE}" \
        -H "Content-Type: application/json" \
        -d "{\"assigneeAgentId\":\"${AGENT_ID}\"}" > /dev/null
    # The status transition is the actual dispatch trigger (assignment alone is not).
    api -X PATCH "${API_URL}/api/issues/${CANDIDATE}" \
        -H "Content-Type: application/json" \
        -d '{"status":"in_progress"}' > /dev/null
    echo "    Dispatched $(api "${API_URL}/api/issues/${CANDIDATE}" | jq -r .identifier) to ${ADAPTER}"
    DISPATCHED=$((DISPATCHED + 1))
done

if command -v python3 > /dev/null && [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
    echo "==> Telegram tokens present: run 'python3 scripts/paperclip_telegram_bridge.py' for approvals."
fi
echo "==> Loop tick complete (${DISPATCHED} dispatched)."

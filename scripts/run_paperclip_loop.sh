#!/usr/bin/env bash
# scripts/run_paperclip_loop.sh
#
# One-command Paperclip hybrid loop tick:
#   1. Ingest open backlog issues into Paperclip (topological, idempotent).
#   2. Dispatch the next dispatchable board issue to an agent, routed by
#      backlog component (plan.md Phase-4 table; default worker: Jules),
#      via assign + status -> in_progress (the status transition triggers
#      the run).
#
# Resolution/review/approvals are handled by the board's native reviewer flow
# plus scripts/paperclip_telegram_bridge.py (run it separately as a daemon).
#
# Environment:
#   PAPERCLIP_API_KEY        API key                  (default: local)
#   PAPERCLIP_API_URL        Board base URL           (default: http://127.0.0.1:3100)
#   PAPERCLIP_COMPANY_ID     Company id               (default: auto-detect)
#   PAPERCLIP_AGENT_ADAPTER  adapter for unrouted components (default: jules)
#   PAPERCLIP_MAX_DISPATCH   max issues per tick      (default: 1)
#
# Usage:
#   ./scripts/run_paperclip_loop.sh [--dry-run]

set -euo pipefail

API_URL="${PAPERCLIP_API_URL:-http://127.0.0.1:3100}"
API_KEY="${PAPERCLIP_API_KEY:-local}"
DEFAULT_ADAPTER="${PAPERCLIP_AGENT_ADAPTER:-jules}"
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

# Serialize ticks: ingest and dispatch must not interleave between cron/systemd
# invocations (orchestrator had the same single-daemon invariant via its state file).
exec 9>/tmp/paperclip_loop.lock
if ! flock -n 9; then
    echo "Another loop tick is already running; exiting."
    exit 0
fi

echo "==> [1/3] Ingesting backlog DAG into Paperclip"
PAPERCLIP_API_URL="${API_URL}" PAPERCLIP_API_KEY="${API_KEY}" \
    kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts "$@"

COMPANY_ID="${PAPERCLIP_COMPANY_ID:-$(api "${API_URL}/api/companies" | jq -r '.[0].id')}"
if [ -z "${COMPANY_ID}" ] || [ "${COMPANY_ID}" == "null" ]; then
    echo "ERROR: could not resolve company id" >&2
    exit 1
fi

ROSTER=$(api "${API_URL}/api/companies/${COMPANY_ID}/agents")

agent_for_component() {
    # plan.md Phase-4 routing table. Keys are `component:` values from issue
    # frontmatter, surfaced on the board as "**Component:** …" in descriptions.
    local component="$1"
    local url_key
    case "${component}" in
        enforcer|kernel|seccomp|landlock|ffm|portal)
            url_key="antigravity-acp-developer" ;;          # deep reasoning, FFM precision
        profiler|shell|podman|container|platform)
            url_key="implementation-software-developer-grok" ;;
        docs|architecture|spec|design)
            url_key="founding-systems-security-engineer" ;; # acceptance criteria owner
        *)
            url_key="" ;;
    esac
    if [ -n "${url_key}" ]; then
        printf '%s' "${ROSTER}" | jq -r --arg k "${url_key}" \
            '[.[] | select(.urlKey == $k)][0].id? // empty'
    else
        printf '%s' "${ROSTER}" | jq -r --arg a "${DEFAULT_ADAPTER}" \
            '[.[] | select(.adapterType == $a)][0].id? // empty'
    fi
}

echo "==> [2/3] Routing table ready (default adapter: ${DEFAULT_ADAPTER})"

if [ "${DRY_RUN}" == "true" ]; then
    echo "==> Dry-run dispatch preview (top 5 candidates):"
    api "${API_URL}/api/companies/${COMPANY_ID}/issues" | ROSTER_JSON="${ROSTER}" DEFAULT_ADAPTER="${DEFAULT_ADAPTER}" python3 -c '
import json, os, re, sys

rank = {"high": 2, "medium": 1, "low": 0}
routes = {
    ("enforcer", "kernel", "seccomp", "landlock", "ffm", "portal"): "antigravity-acp-developer",
    ("profiler", "shell", "podman", "container", "platform"): "implementation-software-developer-grok",
    ("docs", "architecture", "spec", "design"): "founding-systems-security-engineer",
}
default_adapter = os.environ.get("DEFAULT_ADAPTER", "jules")
roster_keys = {a["adapterType"]: a["urlKey"] for a in json.loads(os.environ["ROSTER_JSON"])}
issues = json.load(sys.stdin)
candidates = [
    i for i in issues
    if i.get("status") == "backlog"
    and "mazewall:backlog-file=" in (i.get("description") or "")
    and not i.get("assigneeAgentId")
    and all(b.get("status") in ("done", "cancelled") for b in i.get("blockedBy", []))
]
candidates.sort(key=lambda i: (-rank.get(i.get("priority", "low"), 0), i["issueNumber"]))
for i in candidates[:5]:
    m = re.search(r"\*\*Component:\*\* (\S+)", i.get("description") or "")
    component = m.group(1) if m else ""
    url_key = next((v for keys, v in routes.items() if component in keys), None)
    target = url_key if url_key else "adapter:" + default_adapter + " (" + roster_keys.get(default_adapter, "?") + ")"
    print("  would dispatch %s component=%r -> %s" % (i["identifier"], component, target))
'
    echo "==> Dry-run complete."
    exit 0
fi

echo "==> [3/3] Selecting dispatchable issues"
DISPATCHED=0
while [ "${DISPATCHED}" -lt "${MAX_DISPATCH}" ]; do
    CANDIDATE_JSON=$(api "${API_URL}/api/companies/${COMPANY_ID}/issues" | python3 -c '
import json, re, sys

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
if candidates:
    c = candidates[0]
    m = re.search(r"\*\*Component:\*\* (\S+)", c.get("description") or "")
    print(c["id"], m.group(1) if m else "-")
')
    if [ -z "${CANDIDATE_JSON}" ]; then
        echo "    No unassigned, unblocked 'backlog' issues left."
        break
    fi
    read -r CANDIDATE COMPONENT <<< "${CANDIDATE_JSON}"

    AGENT_ID="$(agent_for_component "${COMPONENT}")"
    if [ -z "${AGENT_ID}" ]; then
        echo "    ERROR: no roster agent for component '${COMPONENT}' (and no ${DEFAULT_ADAPTER} fallback)." >&2
        exit 1
    fi

    api -X PATCH "${API_URL}/api/issues/${CANDIDATE}" \
        -H "Content-Type: application/json" \
        -d "{\"assigneeAgentId\":\"${AGENT_ID}\"}" > /dev/null
    # The status transition is the actual dispatch trigger (assignment alone is not).
    api -X PATCH "${API_URL}/api/issues/${CANDIDATE}" \
        -H "Content-Type: application/json" \
        -d '{"status":"in_progress"}' > /dev/null
    IDENTIFIER=$(api "${API_URL}/api/issues/${CANDIDATE}" | jq -r .identifier)
    URL_KEY=$(printf '%s' "${ROSTER}" | jq -r --arg id "${AGENT_ID}" '[.[] | select(.id == $id)][0].urlKey')
    echo "    Dispatched ${IDENTIFIER} (component=${COMPONENT}) -> ${URL_KEY}"
    DISPATCHED=$((DISPATCHED + 1))
done

if command -v python3 > /dev/null && [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
    echo "==> Telegram tokens present: run 'python3 scripts/paperclip_telegram_bridge.py' for approvals."
fi
echo "==> Loop tick complete (${DISPATCHED} dispatched)."

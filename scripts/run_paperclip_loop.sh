#!/usr/bin/env bash
# scripts/run_paperclip_loop.sh
#
# One-command Paperclip hybrid loop tick:
#   1. Ingest open backlog issues into Paperclip (topological, idempotent).
#   2. Run the Kotlin supervisor tick: dispatch the next dispatchable board
#      issue(s), routed by component (plan.md Phase-4 table; default worker:
#      Jules). The status -> in_progress transition is what triggers the run.
#
# All loop logic lives in :tools:orchestrator (HybridSupervisor.kt); this script
# is only a serialized launcher. CI watch, resolution and Telegram run inside
# supervisor --daemon mode.
#
# Environment (read by ingest script and supervisor):
#   PAPERCLIP_API_KEY / PAPERCLIP_API_URL / PAPERCLIP_COMPANY_ID
#   PAPERCLIP_AGENT_ADAPTER / PAPERCLIP_COMPONENT_ROUTES / PAPERCLIP_MAX_DISPATCH
#
# Usage:
#   ./scripts/run_paperclip_loop.sh [--dry-run]

set -euo pipefail

exec 9>/tmp/paperclip_loop.lock
if ! flock -n 9; then
    echo "Another loop tick is already running; exiting."
    exit 0
fi

echo "==> [1/2] Ingesting backlog DAG into Paperclip"
PAPERCLIP_API_KEY="${PAPERCLIP_API_KEY:-local}" \
    kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts "$@"

if [ "${1:-}" == "--dry-run" ] || [ "${1:-}" == "-n" ]; then
    echo "==> [2/2] Supervisor dispatch preview:"
else
    echo "==> [2/2] Supervisor dispatch"
fi

SUP_ARGS_FILE=$(mktemp /tmp/paperclip_supervisor_args.XXXXXX)
trap 'rm -f "$SUP_ARGS_FILE"' EXIT
for arg in "$@"; do printf '%s\n' "$arg" >> "$SUP_ARGS_FILE"; done

./gradlew -q :tools:orchestrator:supervisor -PincludeOrchestrator=true \
    "-PsupervisorArgsFile=${SUP_ARGS_FILE}"

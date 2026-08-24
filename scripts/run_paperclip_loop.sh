#!/usr/bin/env bash
# scripts/run_paperclip_loop.sh
#
# One-command Paperclip hybrid loop:
#   1. Ingest open backlog issues into Paperclip (topological, idempotent).
#   2. Run the Kotlin supervisor: dispatch ticks routed by component (plan.md
#      Phase-4 table; default worker: Jules). --daemon additionally runs CI
#      watch, resolution and the Telegram doorbell/approval buttons.
#
# All loop logic lives in :tools:orchestrator (HybridSupervisor.kt); this script
# is only a serialized launcher.
#
# Environment (read by ingest script and supervisor):
#   PAPERCLIP_API_KEY / PAPERCLIP_API_URL / PAPERCLIP_COMPANY_ID
#   PAPERCLIP_AGENT_ADAPTER / PAPERCLIP_COMPONENT_ROUTES / PAPERCLIP_MAX_DISPATCH
#   PAPERCLIP_TICK_SECONDS / TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID
#
# Usage:
#   ./scripts/run_paperclip_loop.sh [--dry-run] [--force] [--daemon]

set -euo pipefail

exec 9>/tmp/paperclip_loop.lock
if ! flock -n 9; then
    echo "Another loop tick is already running; exiting."
    exit 0
fi

# Flag split BEFORE any invocation: the ingest script only understands
# --dry-run/--force; supervisor-only flags must never reach it. Dry-run gates
# BOTH stages.
INGEST_ARGS=()
SUPERVISOR_ARGS=()
for arg in "$@"; do
    case "${arg}" in
        --dry-run|-n) INGEST_ARGS+=("${arg}"); SUPERVISOR_ARGS+=("${arg}") ;;
        --force|-f)   INGEST_ARGS+=("${arg}") ;;
        *)            SUPERVISOR_ARGS+=("${arg}") ;;
    esac
done

echo "==> [1/2] Ingesting backlog DAG into Paperclip"
PAPERCLIP_API_KEY="${PAPERCLIP_API_KEY:-local}" \
    kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts \
    ${INGEST_ARGS[@]+"${INGEST_ARGS[@]}"}

if printf '%s\n' ${INGEST_ARGS[@]+"${INGEST_ARGS[@]}"} | grep -q '^--dry-run$\|^-n$'; then
    echo "==> [2/2] Supervisor dispatch preview:"
else
    echo "==> [2/2] Supervisor dispatch"
fi

SUP_ARGS_FILE=$(mktemp /tmp/paperclip_supervisor_args.XXXXXX)
trap 'rm -f "$SUP_ARGS_FILE"' EXIT
for arg in ${SUPERVISOR_ARGS[@]+"${SUPERVISOR_ARGS[@]}"}; do
    printf '%s\n' "$arg" >> "$SUP_ARGS_FILE"
done

./gradlew -q :tools:orchestrator:supervisor -PincludeOrchestrator=true \
    "-PsupervisorArgsFile=${SUP_ARGS_FILE}"

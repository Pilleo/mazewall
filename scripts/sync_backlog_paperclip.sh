#!/usr/bin/env bash
set -euo pipefail

# 1-line CLI trigger delegating to Paperclip Task Scheduler Plugin
PAPERCLIP_URL="${PAPERCLIP_API_URL:-http://127.0.0.1:3100}"
BACKLOG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docs/internals/backlog" && pwd)"

COMPANY_ID=$(curl -s "${PAPERCLIP_URL}/api/companies" | node -e '
  const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
  process.stdout.write(data[0]?.id || "");
')

if [ -z "${COMPANY_ID}" ]; then
  echo "❌ Error: No company found on Paperclip server at ${PAPERCLIP_URL}"
  exit 1
fi

PLUGIN_ID=$(curl -s "${PAPERCLIP_URL}/api/plugins" | node -e '
  const data = JSON.parse(require("fs").readFileSync(0, "utf8"));
  const p = data.find(x => x.pluginKey === "paperclip.task-scheduler");
  process.stdout.write(p?.id || "");
')

if [ -z "${PLUGIN_ID}" ]; then
  echo "❌ Error: Plugin paperclip.task-scheduler is not installed on Paperclip server"
  exit 1
fi

echo "🔄 Triggering Plugin Backlog Sync for directory: ${BACKLOG_DIR}..."
curl -s -X POST "${PAPERCLIP_URL}/api/plugins/${PLUGIN_ID}/actions/sync-repo-backlog" \
  -H "Content-Type: application/json" \
  -d "{
    \"companyId\": \"${COMPANY_ID}\",
    \"params\": {
      \"backlogDir\": \"${BACKLOG_DIR}\"
    }
  }" | node -e '
  const res = JSON.parse(require("fs").readFileSync(0, "utf8"));
  if (res.data) {
    console.log("✅ Sync Successful!");
    console.log(`   📂 Discovered: ${res.data.discoveredCount}`);
    console.log(`   ➕ Created:    ${res.data.createdCount}`);
    console.log(`   🔄 Updated:    ${res.data.updatedCount}`);
    console.log(`   🔒 Lock Edges: ${res.data.totalConflictEdges}`);
  } else {
    console.error("❌ Sync Failed:", res);
    process.exit(1);
  }
'

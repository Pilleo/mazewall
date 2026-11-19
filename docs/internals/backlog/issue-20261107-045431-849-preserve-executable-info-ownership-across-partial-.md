---
title: "Preserve executable-info ownership across partial map-installation failures"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/hotspot-agentless/patches/profiler-target-bootstrap.patch"
target_symbols: 
  - "ExecutableInfoManager.AddOrIncRef"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve executable-info ownership across partial map-installation failures

**Context:**
Checked target bootstrap exposed partial stack-delta page writes without ownership completion, wrong ref.MapID cleanup, ignored cleanup failures, and an asynchronous map worker without observed outcomes. Preserve existing entries and actual acquired map identity; never claim a clean rollback from queue submission. Source assessment: /tmp/opencode/master-merge-mr5fp6z4/inspection-e5v_8k7j/sol-bootstrap-executable-ownership-dependency-plan.json (fb7bda08944786c563a9fc0efed25f49fe68d49a94e619fff3ab21ead19724e0).

**Needed:**
1. Implement the reviewed bounded three-file map-installation/worker completion repair with host-only partial-failure and error-identity tests before checked target bootstrap integration.

**Progress:** Host repair independently accepted in `/tmp/opencode/master-merge-mr5fp6z4/inspection-neiy0m9q/sol-executable-map-ownership-repair-rereview.json` (SHA-256 `85511279445022993fd6b5ed1daba69a4e9e7e21e8e901f74287f5e2f46e2f12`). The canonical overlay now includes checked PM bootstrap and constructor integration. Exact seven-file overlay/tested-postimage comparison: `inspection-o5br_jrj/overlay-equality.json`. Latest four-package host suite: `inspection-hk75161q/command.json`; targeted ownership race tests: `inspection-tqrua8eh`. The final complete project build passed (`inspection-zwz9slpj/full-build.json`); fresh native evidence remains pending and the configured build-engine endpoint is unavailable. This issue stays open and qualification remains 11/17. Inspection paths are under `/tmp/opencode/master-merge-mr5fp6z4/`.

---
<!-- id: issue-20261107-045431-849-preserve-executable-info-ownership-across-partial-  file: issue-20261107-045431-849-preserve-executable-info-ownership-across-partial-.md -->

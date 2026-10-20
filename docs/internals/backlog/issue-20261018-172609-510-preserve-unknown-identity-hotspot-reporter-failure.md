---
title: "Preserve unknown-identity HotSpot reporter failures through the fatal owner"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/structured.go.txt"
target_symbols:
  - "storeTrace"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve unknown-identity HotSpot reporter failures through the fatal owner

**Context:**
Task3 reproduced unknown-definition replacement and processmanager reporter errors being logged without the existing fatal callback; RED receipts are retained in build/kubescape-dev/task-3. Scoped source remedy is implemented with Task3; independent review remains pending.

**Needed:**
1. Review Task3 immutable unknown-definition storage and original-cause callback routing with its RED/GREEN host evidence before closing this issue.

---
<!-- id: issue-20261018-172609-510-preserve-unknown-identity-hotspot-reporter-failure  file: issue-20261018-172609-510-preserve-unknown-identity-hotspot-reporter-failure.md -->

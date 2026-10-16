---
title: "Reject contradictory per CPU conservation snapshots before aggregation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/accounting/conservation.go"
target_symbols:
  - "Aggregate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject contradictory per CPU conservation snapshots before aggregation

**Context:**
Review reproduces CPU1 attempts1 submits2 and CPU2 attempts1 submits0 combining into complete lost0 proof despite impossible per CPU conservation

**Needed:**
1. Validate each CPU tuple before summing with overflow-safe subtraction and keep proof unknown on contradiction

---
<!-- id: issue-20261005-212826-184-reject-contradictory-per-cpu-conservation-snapshot  file: issue-20261005-212826-184-reject-contradictory-per-cpu-conservation-snapshot.md -->

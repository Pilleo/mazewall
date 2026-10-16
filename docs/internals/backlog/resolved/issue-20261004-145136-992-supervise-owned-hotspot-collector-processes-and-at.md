---
title: "Supervise owned HotSpot collector processes and attachment sessions"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
target_symbols:
  - "HotSpotCollectorProcess"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Supervise owned HotSpot collector processes and attachment sessions

**Context:**
Approved milestone 4 needs bounded real child and pipe supervision with streamed immutable snapshots and retained cleanup ownership

**Needed:**
1. Test streamed partial evidence and real/fault-injected child startup finish and pipe retirement before implementing supervision

**Resolution:** Internal backend/ownership session reuses ProcessLauncher with
split machine/diagnostic streams, explicit assets and target generation, stdin
control, bounded concurrent workers, immutable streamed evidence and retained
failed cleanup ownership. Added resource-joined completion and corrected the
prototype summary-object/envelope mismatch under an observed red test. Real child
faults, installed native permission refusal, 400-pair real Kotlin-to-collector
parent-control acceptance and merge gate pass. Public API exposure remains
milestone 5. See [evidence](../../../plans/2026-10-04-mazewall-hotspot-supervision-milestone.md).

---
<!-- id: issue-20261004-145136-992-supervise-owned-hotspot-collector-processes-and-at  file: issue-20261004-145136-992-supervise-owned-hotspot-collector-processes-and-at.md -->

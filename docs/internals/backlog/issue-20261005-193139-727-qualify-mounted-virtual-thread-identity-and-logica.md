---
title: "Qualify mounted virtual thread identity and logical scope"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/qualification/loom"
  - "experiments/hotspot-agentless/fixtures/java/VirtualThreadOracle.java"
  - "experiments/hotspot-agentless/internal/candidate/runner.go"
  - "experiments/hotspot-agentless/internal/candidate/verify.go"
  - "experiments/hotspot-agentless/tests/unit"
  - "docs/plans/2026-10-05-hotspot-loom-evidence.md"
target_symbols:
  - "Scope"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify mounted virtual thread identity and logical scope

**Context:**
Carrier TID cannot identify virtual threads or async parents; capture-time mount and object lifetime proof is required before promotion

**Needed:**
1. Implement conservative identity model and oracle-independent virtual-thread corpus

Model/oracle/native experiment implemented:256tokens observed on1/2/4carrier TIDs,
no event-time virtual logical ID/mount/object proof. Parent history unavailable.
Pinned JavaThread source resolves/replaces OopHandle values; later reads do not
identify an earlier event. Keep production logical-scope promotion blocked. See
[evidence](../../plans/2026-10-05-hotspot-loom-evidence.md).

---
<!-- id: issue-20261005-193139-727-qualify-mounted-virtual-thread-identity-and-logica  file: issue-20261005-193139-727-qualify-mounted-virtual-thread-identity-and-logica.md -->

---
title: "Bound Tier E retention and make lifecycle terminal"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files: ["profiler/src/main/kotlin/io/mazewall/profiler/attribution/TierEProfilerSession.kt"]
target_symbols: ["TierEProfilerSession", "AttributionCollector", "TierEOptions"]
open_questions: false
---

# Bound Tier E retention and make lifecycle terminal

**Context:** Repeated finish reread definitions and appended telemetry; pending and retained records
were unbounded, and two unrelated collectors shared the same name.

**Needed:** Model Collecting/Finished/Closed, cache finish, bound both stores, account losses, and name
raw retention separately from observation translation.

**Resolution:** Tier E now has a terminal lifecycle, 100,000-record configurable defaults, explicit
overflow loss accounting, `TierERingBufferCollector` and `ProfileObservationEbpfCollector` names,
plus deprecated migration shims for both old `LiveEbpfCollector` entry points.

---
<!-- id: issue-20260910-000105-bound-tier-e-retention-and-lifecycle  file: issue-20260910-000105-bound-tier-e-retention-and-lifecycle.md -->

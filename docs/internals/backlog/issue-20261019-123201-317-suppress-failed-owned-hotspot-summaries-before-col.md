---
title: "Suppress failed owned HotSpot summaries before collector error publication"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/hotspot-agentless/internal/capture/collector.go"
  - "experiments/hotspot-agentless/internal/capture/collector.go"
target_symbols:
  - "Run"
open_questions: false
---

# 🟡 [Severity: HIGH]: Suppress failed owned HotSpot summaries before collector error publication

**Context:**
PR540 Task1: first failing probe .superpowers/sdd/2026-10-19-pr540-review-next-steps/task-1-wrapper-red.log reproduces sole EACCES loss in both schema2 and schema3. Actual Run exit statements write an invalid summary before the existing collector_error wrapper; the local SDK decoder retains ErrFinality instead of EACCES. Pending-owner controls pass. Original evidence: build/kubescape-dev/pr540-review-20261019.

**Needed:**
1. Add publication-boundary tests, suppress only failed owned summaries in both native source owners, preserve schema1 diagnostics and pending-owner handling, and verify the real JSON sink/SDK decoder plus full commit hook.

---
<!-- id: issue-20261019-123201-317-suppress-failed-owned-hotspot-summaries-before-col  file: issue-20261019-123201-317-suppress-failed-owned-hotspot-summaries-before-col.md -->

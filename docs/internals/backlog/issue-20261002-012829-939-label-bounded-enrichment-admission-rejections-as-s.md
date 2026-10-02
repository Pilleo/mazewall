---
title: "Label bounded enrichment admission rejections as skipped terminals"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/internal/capture"
  - "experiments/hotspot-agentless/results/**"
  - "experiments/hotspot-agentless/README.md"
target_symbols:
  - "Enricher.Submit"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Label bounded enrichment admission rejections as skipped terminals

**Context:**
The 10000-operation pressure run increments skipped for pending_count_limit but emits attribution unavailable. Strict terminal-counter validation rejects the summary: 2166 skipped were delivered as unavailable, obscuring queue pressure.

**Needed:**
1. Strengthen existing count/byte admission tests to require skipped attribution, correct the terminal label while retaining reason and bounds, and re-run pressure reporting.

**Implemented evidence (2026-10-02):**
Both count/byte admission tests first rejected the unavailable label, then passed
with explicit skipped attribution. Queue limits/reasons and delivered operation
records are unchanged. The corrected pressure report separates 735 measured
skips from 4569 missing operations and zero measured unavailable outcomes; the
strict summary counter validation now passes while the missing-token gate still
fails. Original incorrect records in `benchmark.8b6ad46e` remain immutable.
See [`event-read-loss.md`](../../../experiments/hotspot-agentless/results/event-read-loss.md).
Keep open for review of the experimental reporting change.

---
<!-- id: issue-20261002-012829-939-label-bounded-enrichment-admission-rejections-as-s  file: issue-20261002-012829-939-label-bounded-enrichment-admission-rejections-as-s.md -->

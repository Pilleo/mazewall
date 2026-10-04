---
title: "Add bounded Kotlin replay and incomplete coverage for HotSpot collector evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add bounded Kotlin replay and incomplete coverage for HotSpot collector evidence

**Context:**
Approved MazeWall-first plan milestone 1 needs immutable typed event/frame evidence, exact session/occurrence/generation pairing, bounded schema-1 JSONL replay, retention/terminal accounting and coverage that cannot become a complete policy. No collector launch or strategy dispatch in this milestone.

**Needed:**
1. Write red replay/identity/immutability/retention/coverage tests using a genuine compact fixture, implement internal models/reader/assembler/coverage adapter, and verify legacy API/policy compatibility.

**Resolution:** Internal Kotlin models/reader/mapper/assembler and incomplete coverage
adapter are implemented. 14 focused tests and the full profiler/build/quality gates
pass. Reordering/shared references retain identity; duplicate/mismatched/unpaired
records fail. Recovery retention, known-loss lower bound and depth violations were
observed red before correction. Public APIs, strategy dispatch and native launch
remain outside this milestone. See
[`milestone evidence`](../../../plans/2026-10-03-mazewall-hotspot-evidence-milestone.md).

---
<!-- id: issue-20261003-200852-719-add-bounded-kotlin-replay-and-incomplete-coverage-  file: issue-20261003-200852-719-add-bounded-kotlin-replay-and-incomplete-coverage-.md -->

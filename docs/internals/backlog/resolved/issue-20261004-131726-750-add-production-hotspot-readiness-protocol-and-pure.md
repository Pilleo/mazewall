---
title: "Add production HotSpot readiness protocol and pure session lifecycle"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot"
  - "profiler/hotspot-agentless"
  - "experiments/hotspot-agentless"
target_symbols:
  - "HotSpotSessionMachine"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add production HotSpot readiness protocol and pure session lifecycle

**Context:**
Approved milestone 3 must distinguish validated native readiness from early attachment and refuse fabricated stop completion

**Needed:**
1. Test readiness and finite single-owner lifecycle transitions before wiring a versioned collector handshake

**Resolution:** Explicit production schema 2, actual native/runtime/generation
ready checks, optional parent control pipe with EOF abort and checked SIGTERM
drain, typed permission diagnostics, strict target-exit failure, finite pure
Kotlin state/effects and profiler closed-set architecture test. Focused/race/
native tests, version-2 installed asset/capture controls and full merge gate pass.
Actual Kotlin process I/O supervision remains milestone 4. See
[evidence](../../../plans/2026-10-04-mazewall-hotspot-protocol-milestone.md).

---
<!-- id: issue-20261004-131726-750-add-production-hotspot-readiness-protocol-and-pure  file: issue-20261004-131726-750-add-production-hotspot-readiness-protocol-and-pure.md -->

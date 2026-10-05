---
title: "Qualify pinned HotSpot JIT and GC lifetime candidates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/qualification/jitgc"
  - "experiments/hotspot-agentless/fixtures/java/RuntimeCandidateFixture.java"
  - "experiments/hotspot-agentless/scripts/run-capability-candidates.py"
  - "experiments/hotspot-agentless/scripts/verify-runtime-candidate.py"
  - "experiments/hotspot-agentless/tests/unit"
  - "docs/plans/2026-10-05-hotspot-jit-gc-evidence.md"
target_symbols:
  - "Evaluate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify pinned HotSpot JIT and GC lifetime candidates

**Context:**
Approved candidate qualification needs useful token oracles and compiled or concurrent reclamation proof while production runtime refusal remains intact

**Needed:**
1. Implement offline proof model and exact token fixture verifier, retain per-tuple qualification evidence

Qualification harness/model/native corpus implemented. Pinned C1/Serial28of40,
C2/Serial31of40 and interpreter/G126of40 useful exact application prefixes;
remaining application frames unavailable. Source shows code reclamation/compile IDs
but capture has no compiled generation lease. Keep production promotion blocked;
pressure/deopt/unloading gates await that mechanism. See
[evidence](../../plans/2026-10-05-hotspot-jit-gc-evidence.md).

---
<!-- id: issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida  file: issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md -->

---
title: "Package verified HotSpot distribution provenance and operator example"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless"
target_symbols:
  - "HotSpotDistribution"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Package verified HotSpot distribution provenance and operator example

**Context:**
Approved final milestone needs optional reproducible offline distribution, actual selected license/source provenance, API version pairing and a clean installed public example

**Needed:**
1. Test distribution checksums and refusal before building a source/license-complete local release artifact

**Resolution:** Optional verified distribution, actual selected-module notices and
corresponding patched native source, public installed example, supported ordinary
JVM/JIT-refusal runs and build/lint/ABI gates pass. See
[evidence](../../../plans/2026-10-05-mazewall-hotspot-distribution-milestone.md).

---
<!-- id: issue-20261005-122913-554-package-verified-hotspot-distribution-provenance-a  file: issue-20261005-122913-554-package-verified-hotspot-distribution-provenance-a.md -->

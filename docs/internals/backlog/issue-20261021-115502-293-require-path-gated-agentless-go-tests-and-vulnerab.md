---
title: "Require path-gated agentless Go tests and vulnerability checks in CI"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: ["profiler"]
target_files:
  - ".github/workflows/ci.yml"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "profiler/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/Makefile"
  - "profiler/hotspot-agentless/internal/capture/fatal_readout_test.go"
  - "experiments/hotspot-agentless/internal/capture/fatal_readout_test.go"
  - "profiler/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/README.md"
  - "docs/internals/designs/core/testing-strategy.md"
  - "profiler/hotspot-agentless/go.mod"
  - "profiler/hotspot-agentless/go.sum"
  - "profiler/hotspot-agentless/dependencies.lock.json"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/dependencies.lock.json"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotNativeAttachTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotAcceptanceHarness.kt"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: HIGH]: Require path-gated agentless Go tests and vulnerability checks in CI

**Context:**
Automate both Go modules' race tests and pinned govulncheck only for relevant changes.
Fix capture test subprocesses to use the prepared modfile so ordinary make unit works.
Require Go job success in the publication join and preserve failures from either module.
Use Go1.26.10 and patched x/net with its minimum required x/* versions; keep locked
images and native fixtures aligned. Containerd findings remain blocking while their
unreviewed v1 attribution is disputed upstream; do not suppress scanner failures.

**Needed:**
1. Fix and regression-check ordinary make unit in both modules.
2. Add pinned vulnerability target and bounded, path-gated required CI job; test actual workload failure propagation and publication join.
3. Verify local unit/policy/full-build checks; keep real vulnerability findings blocking until separately remediated.

---
<!-- id: issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab  file: issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab.md -->

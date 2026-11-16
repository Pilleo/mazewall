---
title: "Investigate Tier E delayed artifact coverage dropping one of 72 declared paths"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt"
target_symbols:
  - "TierERootfulContainerEndpointIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate Tier E delayed artifact coverage dropping one of 72 declared paths

**Context:**
Unchanged configured full hook during documentation-only B3/C1 checkpoint failed delayed closed artifact case: expected72 recovered71 missing_paths[43], no admission errors/wrong edges, policy_eligibility:false. Original failing XML/full log and actual retained owner states frozen under build/kubescape-dev/local-cluster/checkpoint-tier-e-coverage-failure. Exact same focused test then passed without source/config/deadline/threshold changes. Do not relabel original capture or permit incomplete coverage.

**Needed:**
1. Trace the retained original event/artifact/reader evidence to explain the missing declared edge, add a discriminating regression if reproducible, preserve strict72/72 requirement and original owner custody

## 2026-11-03 A3 investigation (open)

The actual v1/uprobe, full-stream, recovery-disabled, live-off consumer was traced;
no deterministic causal counterexample was found. Full terminal decoding precedes
session finalization, final ring drain precedes definition admission, and the
joiner revisits both definition orders. Historical raw data is irrecoverably lost,
so no causal fix is justified. The current full-gate delayed-case pass does not
diagnose historical 71/72 or qualify the affected reference lane. Strict 72/72
remains unchanged; no standalone repeat acquisition or instrumentation was
launched, and no threshold, timeout or expected count changed. See the local
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a3-investigation-report.md`.

---
<!-- id: issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp  file: issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp.md -->

---
title: "Allow admission overlap in nested containment evidence regression"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
target_symbols:
  - "ManagedRuntime.callContained"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Allow admission overlap in nested containment evidence regression

**Context:**
CI job116402834484 at80b67b5e fails OwnedRuntimeTest line601 with RejectedExecutionException. The evidence-reuse test uses one physical worker and one admission, then immediately starts a follow-up after result delivery. ManagedRuntime.awaitInvocation waits result(), while OwnedInvocation releases admission later before executionEnd(). The setup accidentally tests a timing assumption instead of evidence isolation; preserve one worker and the existing contamination/denial assertions.

**Needed:**
1. Use a bounded second admission for the queued follow-up, preserving one physical worker and all existing containment evidence checks; run the owning runtime tests.

## Resolution

The test now keeps one physical worker and reserves a second bounded admission
for its follow-up. This respects the public separation between result delivery
and execution end: the prior invocation may retain its slot briefly after the
caller receives the result. The follow-up queues on the same worker; no retry,
sleep, admission-release shortcut or production behavior change is introduced.
All denial and evidence-isolation assertions remain.

All33 OwnedRuntimeTest cases pass without skips (`run-a324bf13e562`,
`run-62b97fa12d30`). Other tests still require one-slot admission to remain held
until explicit execution end. CI's original rejection is retained in run38729302452.

The full included-module build passes on Ubuntu OpenJDK25.0.4.1
(`run-b7e52d1e97d0`, 357,475 ms). Two local GraalVM full-check worker exits are
recorded separately under issue-20261015-120935-494; this test-only correction
does not claim to resolve that native mapping failure.

---
<!-- id: issue-20261015-115545-513-allow-admission-overlap-in-nested-containment-evid  file: issue-20261015-115545-513-allow-admission-overlap-in-nested-containment-evid.md -->

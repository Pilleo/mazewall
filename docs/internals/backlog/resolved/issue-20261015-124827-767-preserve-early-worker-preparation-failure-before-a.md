---
title: "Preserve early worker preparation failure before all workers start"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
target_symbols:
  - "OwnedDomain.prepare"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve early worker preparation failure before all workers start

**Context:**
Clean CI sometimes reports a preparation TimeoutException instead of the intended installation failure. Startup latch is initialized to requested workers, but an early worker fail shuts down the executor while prestartAllCoreThreads is still creating them. Unstarted workers never count down, so preparation waits for an impossible readiness count and caller timeout masks the failure. Count only acquired workers, preserve FAILED state and original cause, and still join physically created workers.

**Needed:**
1. Add a deterministic regression for a domain failed before prestart, prove it returns the original failure without waiting for the full startup deadline, then account for unstarted latch slots.

## Regression evidence

A deterministic prefailed-generation case exposes the impossible latch wait:
`run-6d9a43047b05` fails the five-second bounded observation while preparation
waits for nonexistent workers. Startup now uses the actual prestart count and
counts down only unstarted slots. FAILED state, original cause, and joining of
all physically acquired workers are preserved. All34 owned-runtime cases pass
(`run-04c0dbbf96d7`), including failure retirement and one-permit metadata reuse
with explicit execution-end barriers. Full verification is the completion gate.

The full included-module build passes on OpenJDK25 (`run-d231eece1093`,674854ms).
No application entry, success readiness, policy fallback or skipped verification
is introduced when startup has already failed.

---
<!-- id: issue-20261015-124827-767-preserve-early-worker-preparation-failure-before-a  file: issue-20261015-124827-767-preserve-early-worker-preparation-failure-before-a.md -->

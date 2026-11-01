---
title: "Await execution end before reusing single admission demo domain"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "demos"
target_modules: []
target_files:
   - "demos/cli-demo/src/main/kotlin/demo/OwnedDemoRuntime.kt"
   - "demos/cli-demo/src/main/kotlin/demo/WorkspaceExample.kt"
   - "demos/cli-demo/src/test/kotlin/demo/OwnedDemoRuntimeAwaitTest.kt"
   - "demos/cli-demo/README.md"
   - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
target_symbols: ["OwnedDemoRuntime.await", "readWorkspaceFile"]
open_questions: false
needs_kernel: true
core_lock: false
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Await execution end before reusing single admission demo domain

**Context:**
PR559 exact-head CI40191302429 fails runWorkspaceExample with Domain admission limit reached after the first successful read. ManagedRuntime deliberately delivers results before final invocation bookkeeping releases admission. The single-admission synchronous demo must await the original handle executionEnd before another call; do not raise capacity, replay/retry, change core delivery semantics or increase total wait budget.

**Needed:**
1. Use controlled result/end futures to show the shared demo await helper returns before executionEnd; cover business interruption and wait-failure retention, then fix the demo barrier and explicit workspace handle path.
2. Preserve the one-worker/one-admission limit, one ten-second total wait deadline, original-handle cooperative cancellation and fatal-first error evidence. Do not retry, replay or change the core result/end contract.
3. Run all CLI tests/presentations, the actual runnable command, exact-source aggregate/full build and updated hosted CI. Treat earlier green runs as insufficient for this new race.

## Reproduction and correction

Downloaded CI XML confirms run `40191302429` returned the first approved workspace
read, then failed the following call with `RejectedExecutionException: Domain
admission limit reached`. Result publication precedes `owner.finished` / admission
release in the intentionally separate core delivery/end contract.

Controlled-stage host tests reproduce RED without scheduler sleeps: the old demo
await returns delivery before observing the still-pending original execution-end
stage. Additional controls cover business InterruptedException identity, nullable
results, timeout/interruption cancellation and fatal-first retained error evidence.
The corrected helper uses one shared ten-second deadline for result and end waits.
End-wait failure cannot be consumed as an expected completed denial; delivered
errors remain attached, and fatal errors retain priority.

**Ruling:** The workspace helper explicitly submits and retains its original
handle, using the existing demo await helper instead of the core delivery-only
synchronous bridge. Core/public API semantics are unchanged. One worker/one
admission remains fixed; there is no polling, sleep, retry, replay or capacity
increase. Existing CLI await callers share the corrected synchronous completion
barrier. The existing optional phase now selects the five host await tests and
the real workspace test, without filtering any previously selected test task.

All 42 CLI module tests, eight native presentations and the actual workspace
command pass without skips. Exact committed producer
`a4aac5dcc759080228458a382e7dc7d580ac78f0` then passes optional acceptance in
8m21s and the full build in 5m09s. Fresh-clone JUnit confirms the five await host
cases, real workspace case, two Spring host cases and four Boot presentations
execute without skips/failures; all three actual commands and prior phases pass.
Updated exact-head hosted CI remains required; the previous failed gate is not waived.

---
<!-- id: issue-20261025-121925-834-await-execution-end-before-reusing-single-admissio  file: issue-20261025-121925-834-await-execution-end-before-reusing-single-admissio.md -->

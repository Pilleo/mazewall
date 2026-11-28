---
title: "Preserve pending application exceptions during native capture cleanup"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler-native/src/agent.cpp"
target_symbols:
  - "execution_context"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve pending application exceptions during native capture cleanup

**Context:**
Repository-wide review, 2026-11-04: [TE-01](../../reviews/2026-11-04-repository-review-findings.md#te-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed source defect · high confidence · release boundary: JVMs running the optional native agent. NEW after backlog/review search.**

- **Trigger/evidence:** a proxied native operation throws, for example `UnixNativeDispatcher.openat0` returning ENOENT. The original call executes before the automatic scope destructor in `profiler-native/src/agent.cpp:1256–1260`. The destructor obtains current execution identity at `agent.cpp:1020–1026`, delegating through `1053–1055` to `execution_context`. That function calls `CallBooleanMethod` and `CallLongMethod` at `768–769` and only checks for an exception afterward (`770–774`). It neither checks for an existing application exception before those calls nor checks between the two calls.
- **Consequence:** ordinary application error handling enters JNI operations prohibited while an exception is pending. The comment promising exception preservation does not make those calls legal. This invalidates the native-call-preservation claim in `docs/internals/designs/profiler/tier-e-design.md:2434–2442`. A specific crash or exception replacement was **not** reproduced; the JNI contract violation is the confirmed finding.
- **Smallest complete correction:** make exceptional scope exit avoid Java-method/metadata calls, conservatively invalidate attribution when execution cannot be revalidated, and retain the original pending exception. Check each fallible JNI call before issuing another. Distinguish profiler-owned failures from application failures rather than clearing all exceptions.
- **Regression:** execute success and failing open/unlink/socket/read/write paths with `-Xcheck:jni`; assert no pending-exception JNI diagnostics, unchanged exception type/errno and return value, and inactive attribution afterward. `profiler-native/tests/NativeOutcomeWorkload.java:20–33` checks the ENOENT result, but `profiler-native/CMakeLists.txt:131–138` neither enables checked JNI nor covers the other bindings.
- **Mapping/dependencies:** no matching issue found. Add to Plan A native preservation work and make it a prerequisite of Plan B B2/B7, which reuses this scope and explicitly promises preservation on exception exit. This is independent of collector cleanup.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

On JDK 25, the checked-JNI regression observed calls with pending application exceptions on file, unlink and socket paths. Exceptional scope exit now clears attribution, retires its local reference and preserves the original throwable without lookup or Java method calls. The focused native suite passed six tests, including the checked-JNI failure controls, original ENOENT, and ordinary return-value and startup controls.

---
<!-- id: issue-20261103-235248-095-preserve-pending-application-exceptions-during-nat  file: issue-20261103-235248-095-preserve-pending-application-exceptions-during-nat.md -->

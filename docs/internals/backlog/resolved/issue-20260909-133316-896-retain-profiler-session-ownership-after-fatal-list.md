---
title: "Retain profiler session ownership after fatal listener cleanup"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilerSession.kt"
target_symbols:
  - "ProfilerSession"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retain profiler session ownership after fatal listener cleanup

**Context:**
Final architecture review I3: a fatal listener close leaves a stale Complete outcome and unregisters unattempted sibling ownership.

**Needed:**
1. Add deterministic multi-listener and callback failure/retry tests, retain typed incomplete ownership, and preserve fatal identity.

**Resolution (2026-09-09):** Listener cleanup publishes fatal diagnostics before rethrowing
the identical error, and consults the original descriptor generation before retaining retry
authority. Session closure callbacks run only after a complete fold succeeds; interrupted
folds retain their service registration, TID resolver, and unattempted listeners. Service
shutdown publishes the aggregate outcome even when a session aborts it. Completed callback
dispatch and native releases are not repeated.

**Evidence:** `ProfilerCleanupTest` contains ten deterministic cases covering ordinary errors,
cancellation, fatal errors before and after descriptor retirement, multiple listeners/sessions,
terminal native errors, and ordinary/fatal closure callbacks. Before the fix, ten cases ran and
seven failed for the missing behavior. After the fix, these ten and the existing service,
runtime, and listener tests passed: 35 tests, no failures/errors/skips, using
`./gradlew --no-daemon --no-build-cache :profiler:test --rerun --tests '*ProfilerCleanupTest'
--tests '*ProfilerServiceTest' --tests '*ProfilerRuntimeTest' --tests '*ProfilerTraceListenerTest'`.
The unchanged final-review probe now retains its session and reports `Incomplete`. Its mock
retires the first descriptor before throwing; the retry preserves that terminal diagnostic
while reaching the unattempted sibling, rather than reporting a false successful shutdown.

---
<!-- id: issue-20260909-133316-896-retain-profiler-session-ownership-after-fatal-list  file: issue-20260909-133316-896-retain-profiler-session-ownership-after-fatal-list.md -->

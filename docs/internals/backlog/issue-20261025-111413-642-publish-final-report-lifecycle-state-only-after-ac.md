---
title: "Publish final report lifecycle state only after actual owner close"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "demos"
target_modules: []
target_files:
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportApplication.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportBootstrap.java"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportVerification.java"
   - "demos/spring-boot-sandbox/README.md"
target_symbols: ["ReportBootstrap.close"]
open_questions: false
needs_kernel: true
core_lock: false
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Publish final report lifecycle state only after actual owner close

**Context:**
PR559 review: a normal report can commit and physically retire within the existing ten-second owner close, yet fail because an earlier 50ms lifecycle stop left an immutable Incomplete/DRAINING result. Reuse the real held-cleanup verification fixture to reproduce stale state at the actual ReportBootstrap.close boundary; do not raise budgets or waive physical retirement.

**Needed:**
1. Change the existing post-held-cleanup verification assertion to exercise ReportBootstrap.close and observe stale lifecycle state RED before correcting close order.
2. Close the actual runtime before publishing the final lifecycle stop result and remove the normal path's early 50ms stop. Preserve incomplete-owner failures, resource retention, real thread joins and the existing ten-second close / 50ms observation budgets.
3. Verify the finished-JAR normal and held-cleanup paths, source-bound aggregate, full build and updated exact-head CI. Merge/publication approval remains separate.

## Correction and evidence

The existing real held-cleanup verification now calls `ReportBootstrap.close()`
after releasing cleanup and joining the actual worker, then requires the final
Complete/CLOSED lifecycle and retired resources. Before correction it fails with
`Exact runtime/resources physical retirement`: actual runtime close returned but
the earlier Incomplete observation remained. This is a deterministic native RED,
not a scheduler delay, fake owner or increased timeout.

The bootstrap now closes the actual runtime first and only then publishes the
lifecycle's fresh final stop result. Normal generation no longer takes an early
50 ms stop snapshot. Existing incomplete-owner errors and genuine resource
ownership remain intact. Two host and four finished-JAR presentation tests plus
the existing ownership main pass after correction, without skips. The ten-second
runtime close, five-second joins and 50 ms held observation are unchanged.

Exact committed producer `3126b772995c5779c86ebb6706d295f8e44b0af7` passes the full
optional integration aggregate in 9m52s, including both Spring host cases, all four
finished-JAR presentation cases, real held-close regression, all three runnable
entry points and the unchanged native/21-case ownership matrix. A scoped independent
review reports no Critical, Important or Minor findings. The full build then passes
in 5m20s with configured prerequisite skips unchanged. Updated exact-head hosted CI
remains required; merge approval stays separate.

---
<!-- id: issue-20261025-111413-642-publish-final-report-lifecycle-state-only-after-ac  file: issue-20261025-111413-642-publish-final-report-lifecycle-state-only-after-ac.md -->

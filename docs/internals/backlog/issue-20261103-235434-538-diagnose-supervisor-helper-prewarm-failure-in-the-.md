---
title: "Diagnose supervisor helper prewarm failure in the full native gate"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt"
target_symbols:
  - "prewarm"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose supervisor helper prewarm failure in the full native gate

**Context:**
BUILD-RUN, repository review 2026-11-04: the single current-tree full build failed :enforcer:integrationTest in OwnedScopingNativeTest before captured-authority assertions. SocketCleanup.await at SocketHelperPool.prewarm:72 exceeded its shared five-second startup-job budget; the daemon exited and ChildStartupTransaction then reported generic readiness failure. Actual failed method duration was 13.3s, not a demonstrated 30s wait. Full log and fresh XML are retained under /tmp/opencode/mazewall-review-2026-11-04/14-build-ci/. Review source anchor b2c9b335720fd0491a8d86917a3b0593b818a4ec plus the dirty-tree manifest; GraalVM25.0.4.1.1 and Linux7.2.11-x64v3-xanmod1. This is an observed gate failure with unresolved initiating cause, not a proved permission prerequisite, authority mismatch or helper leak. See docs/internals/reviews/2026-11-04-repository-review.md.

**Needed:**
1. Retain the original invocation log/XML and acquire per-helper launch, readiness, job-completion and cleanup evidence in a bounded focused reproduction. Distinguish startup scheduling/resource qualification from a lifecycle defect before changing code. Preserve fail-closed startup and physical cleanup; no blind retries, skipped case or deadline increase. Then add the discriminating regression and rerun owning checks and the complete gate.

---
<!-- id: issue-20261103-235434-538-diagnose-supervisor-helper-prewarm-failure-in-the-  file: issue-20261103-235434-538-diagnose-supervisor-helper-prewarm-failure-in-the-.md -->

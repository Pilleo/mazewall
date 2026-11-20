---
title: "Separate production Portal worker packaging from profiler tooling"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: []
target_files:
  - "portals/worker/build.gradle.kts"
target_symbols: []
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Separate production Portal worker packaging from profiler tooling

**Context:**
Architecture assessment finding 5a: worker build.gradle.kts:34-38 includes :profiler; PortalWorkerMain.kt:204-219 converts --complete-profile documents at runtime, although broker readiness at line233 requires the static baseline. This couples the production worker artifact to developer-only tooling; dependency presence does not imply collector execution. Acceptance: production worker dependency closure excludes profiler tooling, profile conversion retains measured coverage and trusted approval requirements, supported CLI/API compatibility has an explicit migration decision, and packaged worker startup/denial/readiness checks pass. Reuse canonical enforcement artifacts where suitable; no new dependency or breaking API change without approval.

**Needed:**
1. Design the smallest packaging separation for diagnostic profile conversion and production enforcement; inventory callers and published closures before selecting a compatibility-preserving implementation.

## Repository review addendum — 2026-11-04 (ARCH-03)

[Finding and acceptance](../reviews/2026-11-04-repository-review-findings.md#arch-03).
The debt remains live and also reaches the published codegen plugin: worker depends
on profiler (`portals/worker/build.gradle.kts:27–31`), while codegen depends on worker
(`portals/codegen/build.gradle.kts:26–32`). Broker-required startup rejects the
optional `--complete-profile` path, yet inherits that diagnostic closure. Profiler
resource production also selects BTF compilation. A downloaded JAR dependency does
not itself execute a collector or require the consumer to invoke Clang.

Inventory actual host/plugin/worker POM and module closures, then approve the smallest
separation and an explicit migration for the existing profile CLI. Qualify the
packaged static worker's readiness, denied access and physical cleanup with profiler
absent; retain diagnostic conversion/completeness refusals. Final Portal adoption
measurements must use the chosen closure. This review makes no dependency or CLI
change and supplies no fresh packaged acceptance.

---
<!-- id: issue-20261028-230731-061-separate-production-portal-worker-packaging-from-p  file: issue-20261028-230731-061-separate-production-portal-worker-packaging-from-p.md -->

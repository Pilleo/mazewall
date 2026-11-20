---
title: "Add a standalone ordinary-JVM HotSpot observation example"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/hotspot/example/HotSpotObservedExample.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/example/HotSpotObservedExampleTest.kt"
  - "profiler/HOTSPOT_AGENTLESS.md"
target_symbols:
  - "HotSpotObservedExample"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add a standalone ordinary-JVM HotSpot observation example

**Context:**
The operator explicitly selected standalone profiler first after intentionally removing the orchestrator. The public observeHotSpot API and observed collector already exist, but the documented installed example covers strict interpretation. Add one thin ordinary-JVM observation example and runbook using the existing API, verified assets, independently supplied target identity and owned cleanup. Historical recovery and four-image delivery are paused. Approved handoff: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/standalone-observation-example-brief.md.

**Resolution — 2026-11-03:**

Added the ordinary-JVM `HotSpotObservedExample`, its13 host-safe regressions,
and the scoped runbook addition. The example uses existing `observeHotSpot`,
validates the explicit independently supplied target identity and literal helper
prefix before acquisition, retains structured evidence and honest failure/loss/
cleanup outcomes, and never terminates the external target. Existing guide
content is preserved byte-for-byte as the prefix. No API, collector, dependency,
build-wiring or native integration fixture changes were needed.

Verification:
- Actual validation RED precedes implementation; compiler and13 example tests
  passed, plus39 connected tests with3 native-dependent skips.
- Independent scoped review: Spec PASS / Quality PASS, no findings, in
  `.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/standalone-observation-example-review.md`.
  Subsequent diffs only correct formatting; primary inspected their full scope.
- Both changed source-set lint checks passed in10.346s; required `./gradlew build`
  passed in423.698s on unchanged final pins. Complete logs, commands and source
  copies: `/tmp/opencode/standalone-final-gates3-3olh902t/`.
- The installed tooling jar contains both example classes byte-identical to
  final compiler output. Final package/source/JUnit receipts:
  `/tmp/opencode/standalone-final-artifacts-plr7n1as/`.
- Scoped whitespace, local-reference and diff checks passed; earlier focused
  logs and failure/correction history remain in the implementation report.

Final SHA256 pins: app
`f5af85a31d5b1822f2d90e0686e929f0b0c9d690685faf025b3e4fcf066b6ac1`,
test `9665e8855811fba887c4b6870d569d6230419a6593cfd1e10978261648f07487`,
guide `31cfe00d49c0a3c6a4c40db9838ed06eb92107ae9caaf19a13310e6ef25ca182`.

This resolves the compiled standalone example/runbook task. Fresh native
execution of the example remains unverified; no target/helper authority or
historical recovery/four-image acceptance is supplied. That route stays paused.

**Original needed work:**
1. Implement the three-file example/test/runbook scope using existing tooling and fake-child test seams; validate arguments before acquisition, persist structured evidence and honest loss/ownership failures, never terminate the external target, and run focused host-safe tests, tooling compilation/installDist and the required full build. No new collector/API/dependency/build wiring or native acceptance run is authorized by this preparation task.

---
<!-- id: issue-20261103-101247-569-add-a-standalone-ordinary-jvm-hotspot-observation-  file: issue-20261103-101247-569-add-a-standalone-ordinary-jvm-hotspot-observation-.md -->

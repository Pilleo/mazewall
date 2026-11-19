---
title: "Expose validated readiness from the standalone HotSpot observer"
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

**Resolved — 2026-11-03:**

The fixed flushed readiness notification, publication/interruption regressions
and runbook are complete. Actual RED was followed by compiler/lint/analysis
GREEN,16 passing example tests with zero skips/failures/errors, and scoped
review PASS. The sole post-review change was verified test-chain formatting.
Primary's single combined `./gradlew build` passed in473.260s; all thirteen
readiness/issue528 source pins and staged diff stayed unchanged. Both installed
example classes match compiled output; all nine classpath jars are hashed.
Final gate/package receipt:
`/tmp/opencode/profiler-combined-final-gate-_xkwd27d/result.json`.
Pending/running wording below records the earlier checkpoints. This closes
the source task, not the separately prepared native run or capability grant.

**Implementation checkpoint — 2026-11-03:**

Primary proved actual RED: the exact readiness regression failed because
`MAZEWALL_OBSERVATION_READY\n` was absent, with one assertion failure and zero
errors/skips in41.833s. Evidence:
`/tmp/opencode/standalone-readiness-red-83248__d/`.

The sole writer completed the approved three-file change and froze its pins:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-c2z6zouy/source-pins.json`.
That directory includes the complete task diff against the pre-RED baseline,
not only the implementation after the failing test. Phased report:
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/standalone-readiness-implementation.md`.
Scoped review passed with no findings. Primary then corrected the four
formatting-only chain-continuation diagnostics through the same sole writer;
the reviewed application/guide and all ten issue528 sources stayed unchanged.
Updated task pins/diffs:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-ah6mzhjr/`.

Primary's affected compiler/lint/`detektTest` checks passed in66.986s, followed
by all16 example tests in21.429s with zero skips/failures/errors. Evidence:
`/tmp/opencode/standalone-readiness-checks2-xhn8e4uh/`. The unchanged tooling
compiler/lint evidence is retained in the preceding check receipt. The one
combined root completion build is running. Issue528's separate ten test
corrections retain their valid57-test and scoped-review evidence.

# 🟡 [Severity: MEDIUM]: Expose validated readiness from the standalone HotSpot observer

**Context:**
Operator approved the bounded readiness notification after selecting current-host disposable-JVM test preparation. The existing gated ObservedWorkload needs a start signal after public observeHotSpot admission, while the compiled example currently enters its timed window silently. Emit exactly MAZEWALL_OBSERVATION_READY plus newline to stdout, explicitly flush and detect PrintStream publication failure after session ownership and before the window; preserve interruption, failure retention and owned cleanup. Eight arguments and public/native contracts stay unchanged. Native bundle/helper permissions and actual execution remain unselected.

**Needed:**
1. Use only the existing example, its host-safe test and runbook. Prove actual RED then GREEN for exact one-time flushed readiness before collection completes, no signal on rejected admission, and cleanup/error retention on failed publication or interruption. Serialize and restore System.out in console tests. Run compiler, both changed-source-set lint checks, detektTest, focused tests, scoped review and the final full build; reuse valid unchanged issue528 evidence and do not launch a native target or helper.

---
<!-- id: issue-20261103-124359-339-expose-validated-readiness-from-the-standalone-hot  file: issue-20261103-124359-339-expose-validated-readiness-from-the-standalone-hot.md -->

---
title: "Restore profiler test Detekt gate without weakening checks"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/DaemonRunnerTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/DaemonStartupRollbackTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/NativeSocketInputStreamTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEBenchmarkBundleBuilderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierENativeArtifactPackageTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierENativeTimingDiagnosticTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierEPhaseReplayTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESourceFingerprintTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierESplitCoverageVerifierTest.kt"
target_symbols:
  - "detektTest"
open_questions: false
---

**Resolved — 2026-11-03:**

The sixteen reproduced test-analysis errors were corrected within the ten
declared test files. Compiler/test lint/`detektTest`,57 owning tests (zero
skips/failures/errors), and the scoped review passed. Primary's single combined
`./gradlew build` then passed in473.260s on unchanged source pins.
Final gate and before/after source/staging evidence:
`/tmp/opencode/profiler-combined-final-gate-_xkwd27d/result.json`.
Focused receipts remain at `/tmp/opencode/profiler-detekt528-checks-dujjs0go/`;
implementation/review reports are in
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/`.
The OPEN/pending wording below records earlier checkpoints. No analysis rule,
suppression, production contract or native authority was changed by this fix.

# 🟡 [Severity: MEDIUM]: Restore profiler test Detekt gate without weakening checks

**Context:**
Host-only HotSpot continuation on 2026-10-23: :profiler:detektTest fails with 16 existing error-level findings outside the changed HotSpot files (11 ImplicitDefaultLocale, 3 SwallowedException, 1 UnreachableCode, 1 TooGenericExceptionThrown). :profiler:spotbugsMain passes. Preserve unrelated source and do not waive rules or claim a complete quality gate from the focused host suite.

**Current continuation — 2026-11-03:**

After the standalone example task completed, primary selected this remaining
host-only profiler gate for the operator's requested continuation. The exact
`./gradlew :profiler:detektTest` command reproduced16 errors in179.460s on the
current checkout:11 ImplicitDefaultLocale,3 SwallowedException,1 UnreachableCode
and1 TooGenericExceptionThrown. These are current results, not reused counts
from the historical reproduction. All ten observed-owner source pins matched
before/after this command.

Retained command/result, full output, SARIF and source copies:
`/tmp/opencode/profiler-detekt528-current-csncf_dw/`. SARIF SHA256:
`d8a390a36f69cdd6d56178ef98aca320a43f93d9f781ab08c7740816eedfa91a`.
It also contains lower-priority findings outside this correction's16 errors.
The successful default root build for task569 does not discharge this
separately tracked test-analysis gate.

`./scripts/adkw blast-radius detektTest` returned exit2/PARTIAL with Codanna
INDEX_STALE/CODANNA_UNAVAILABLE; its single-name hint is not a caller graph.
Evidence: `/tmp/opencode/profiler-detekt528-impact-9m3zcryv/`. Scoped direct
helper/caller inspection is required before each correction.

The sole Sol-medium writer owns only the ten test files declared above and
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/profiler-test-detekt528-implementation.md`.
Preserve fixture semantics, original failures/interruption and current user
edits. No suppression, rule/baseline change, production edit, native acceptance
or historical recovery action is authorized by this correction. Primary runs
the compiler, lint/analysis and meaningful host tests at frozen handoffs, then
the required completion gate and scoped review. This issue remains OPEN.

**Corrected-revision checkpoint — 2026-11-03:**

All ten source pins in
`/tmp/opencode/master-merge-mr5fp6z4/inspection-2dnc98s0/source-pins.json`
matched before/after primary verification. Compiler, test lint and
`detektTest` passed together in192.470s. The selected owning suite passed57
tests with zero skips, failures or errors in35.060s. Complete logs/commands,
JUnit XML and source copies: `/tmp/opencode/profiler-detekt528-checks-dujjs0go/`.
Independent scoped review returned Spec PASS / Quality PASS with no findings:
`.superpowers/sdd/2026-11-02-profiler-delivery-critical-path/profiler-test-detekt528-review.md`.

The ten corrections are now frozen. The operator additionally approved the
separate three-file standalone readiness task339. Primary will run one final
root build once both scoped changes are stable; this issue remains OPEN until
that completion gate. No fresh native acceptance or lower-priority cleanup is
implied by the57 passing host tests.

**Needed:**
1. When separately selected, reproduce `./gradlew :profiler:detektTest` on the actual maintenance snapshot, retain the historical16 findings alongside its actual output and group current findings by owning fixture. Correct them with meaningful focused tests without changing rule configuration or native execution authority; do not assume an unchanged current count.

**Observed owners:** `engine/{DaemonRunnerTest,ProfilerDaemonTest}.kt`,
`internal/{DaemonStartupRollbackTest,NativeSocketInputStreamTest}.kt`, and
`tierE/tools/{TierEBenchmarkBundleBuilderTest,TierENativeArtifactPackageTest,TierENativeTimingDiagnosticTest,TierEPhaseReplayTest,TierESourceFingerprintTest,TierESplitCoverageVerifierTest}.kt`.
All paths are under `profiler/src/test/kotlin/io/mazewall/profiler/`.

**Evidence:** The unchanged owning files have no task diff. The failed
`./gradlew :profiler:spotbugsMain :profiler:detektTest --continue` output is retained
at `/home/leanid/.local/share/opencode/shell/e8072f47d6b21f157fe39d9b2a242843a3db9422/sh_165ab1f34001vO5HXrGGExWuDE.out`.
HotSpot's source-only checks and explicit native skips do not discharge this gate.

**Actual subsequent reproduction (2026-10-23, pre-Task3-fix1):**
`./gradlew --offline :profiler:detektTest` started at
`2026-10-23T17:30:06.117592Z`, terminal exit1 after44.683s. M HEAD was
`a28cbe293289f2549858aa232d9e6ebd7c4756dd` **plus** the frozen Task3 four-file
snapshot, not HEAD alone: its snapshot-relative diff SHA-256 was
`82bebabc9f542bac9b8730ed5be2e70a7db6ecfdcd44002af2fbdd9dee09c182`;
helper/test hashes were `c1a8b701f87de5fa41bedac051e52b8fbf23e7de37d1c59aac436c9f332c1fd9`
and `df22ca707ea4d26a558ae35a56e0dbd392cbf7bb1f49b7aed290654ecbc9f250`.
The native fixture/guidance hashes are also bound in the receipt below.

Nonpublic operator-custody receipt (not a publication hyperlink):
`.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/private/task-3-artifacts/final-detekt/receipt.json`,
SHA-256 `c6d7a0d0790c34141533e00f5bc8eb02755fa701f17231f6206723d3c6c8d790`.
Its sibling `stdout-stderr.log` has SHA-256
`e6fc6a767aa1e7d3ab9ee27b8e472e63c513d7ce787b4ddc235f3354a07dc987`;
`error-and-owned-warning-summary.json` and `detekt-test.xml` retain that run's
16 errors:11 ImplicitDefaultLocale,3 SwallowedException,1 UnreachableCode and
1 TooGenericExceptionThrown in the owners listed above.

This is a **revision-bound reproduction, not a current-count claim after Task3
fix1**. No maintenance source fix, new Detekt run or full build is selected by
Task8's docs-only handoff. The two separate native-coordinator warnings
(complexity21/15, length111/100) remain deferred Minor triage in the
[current four-lane handoff](../../reviews/2026-10-23-hotspot-runtime-qualification.md#historical-strict-full-quality-and-custody),
not this issue's16 errors. Keep open; no suppression, rule/baseline weakening or
native/full-quality waiver is permitted.

---
<!-- id: issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin  file: issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md -->

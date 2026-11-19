---
title: "Preserve Tier E resource ownership until cleanup completes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEDaemonConfiguration.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorRuntime.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorService.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorLoop.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierERecoveryReader.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorRuntimeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorServiceTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEProfilerSessionLifecycleTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierERecoveryReaderTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierERecoveryRetirementTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEDaemonFinalizationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/engine/BpfProgLoadLayoutTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETargetRegistrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierETerminalReportFileSinkTest.kt"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierERecoveryReader.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/CoverageRecoveryScanner.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierERecoveryReaderTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorRuntimeTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorServiceTest.kt"
target_symbols:
  - "TierEDaemonService"
  - "TierECollectorRuntime"
  - "TierECollectorService"
  - "TierECollectorLoop"
  - "TierEProfilerSession"
  - "TierERecoveryReader"
  - "TierEbpfEngine"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve Tier E resource ownership until cleanup completes

**Context:**
Static resource-safety review found activeSession cleared before retirement and finish failures suppressed; collector close marks ownership released before session cleanup succeeds.

**Needed:**
1. Add fault-injection coverage for failed finalization and retryable native cleanup across daemon, runtime, loop, and session owners.
2. Retain a closing owner until all resources are released; distinguish collection termination from successful resource retirement.
3. Attempt independent cleanup after failed finalization, preserve primary/suppressed failures and fatal identity, and reject replacement attachment while retirement remains incomplete.
4. Make repeated close retry only outstanding resources without repeating successful releases.

November A1 execution includes a once-only registered `FINALIZATION_FAILED` outcome
and retryable physical retirement. Reuse the existing report projection and platform
cleanup helpers. A2 caching/shutdown and A4 permissions/helper work are outside this
scope. If another exact file is required, extend the declared scope before editing it.

## Evidence and failure scenario

Static review on 2026-09-26; not yet runtime-reproduced:

- `TierEKotlinDaemon.kt:415–440`: `finishActiveSession` clears `activeSession` before terminal-watch closure/finalization and discards `runtime.finish()` failure with `runCatching(...).getOrNull()`.
- `TierECollectorLoop.kt:136–142`: `close()` sets `closed = true` before `session.close()`, preventing another close attempt after failure.
- `TierEProfilerSession.kt:919–929`: terminal detachment, quiescence, and counter reads precede source closure and can fail before it is reached.

A DETACH/SHUTDOWN or target-finalization failure can leave native descriptors and ring mappings live after their enclosing owner is forgotten. Reader-level retryable unmapping does not help if the enclosing loop refuses another close.

## Acceptance criteria and validation

- Fault injection covers interrupted collector join, marker detachment, terminal counter reads, and first-attempt unmap failure.
- Incomplete retirement is observable and retains reachable cleanup ownership; no replacement session bypasses it.
- Retry releases outstanding resources exactly once, preserves earlier failure evidence, and does not publish a stale successful terminal result.
- Extend the existing collector lifecycle and daemon tests; retain `BpfRingBufferReaderTest` failed-unmap retry coverage through the higher-level owners.
- Run focused profiler host tests, then `:profiler:test` and the merge gate. Run kernel verification if implementation changes native attachment/notification behavior.

## November A1 implementation evidence

Review fix round 1 extends exact native cleanup scope to `TierEbpfEngine.kt` and its owning
`BpfProgLoadLayoutTest.kt` before edits: preserve fatal throwable identity through native cleanup
and acquisition rollback with the existing cleanup fold, without changing downcalls/layouts,
independent releases, mapping ownership or raw-FD clear/never-retry rules. Also cover real blocked
poll metrics/control liveness and responsibility extraction to pass the unchanged warning ratchet.
No warning inventories, gates or unrelated test fixes are in scope.
Move the unchanged environment-option parsing functions into `TierEDaemonConfiguration.kt` as
part of separating configuration from command/finalization responsibilities; this keeps the
extracted command service within the existing per-file function limit without new suppression.

Round 1 evidence: real interruption-resistant drains inside the actual poll reproduced blocked
runtime finalization and daemon control; snapshot publication and lock-free immutable epoch access
now pass both bounded regressions. Native-seam RED cases reproduced ordinary unmap/acquisition
failures hiding later fatal close errors; engine aggregation now throws the identical fatal object,
retires independent descriptors once and retries only failed mappings. Command, publication,
retirement and event-projection responsibilities were extracted without warning-inventory or rule
changes. `:profiler:detektMain :detektWarningRatchet` passed with **0 added profiler warnings**;
the final 12-class selection passed 118 tests without skips, and the whole profiler suite passed
1,467 executed tests (1,471 discovered, four unchanged skips). Exact evidence is appended to
`a1-report.md` under fix round 1 and retained in `a1-fix1-*.log`. At that point independent review
and the full build remained controller-owned.

The current implementation retains daemon acquisition/closing owners, separates terminal evidence
from physical retirement through the runtime/service/loop/session chain, retries outstanding
cleanup only, and preserves fatal-first and interrupted cleanup evidence. Registered finalization
failure uses one existing incomplete terminal report (`FINALIZATION_FAILED`, actual identity,
zero claimed resolved syscalls and all proof dimensions false); no profile/event artifact is
manufactured. Control errors and `STATUS` retain the failure after cleanup succeeds.

The initial RED daemon regression reproduced a false `OK` on failed finalization. Lower-owner RED
tests reproduced lost unmap retry, independent clock cleanup, interrupted join and failed-counter
outcome retention. Additional RED cases covered fatal acquisition cleanup, interruption combined
with fatal cleanup, resumed collection after failed close, and swallowed terminal publication
failure. Command/output logs and the integrating implementer's report are retained under
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a1-*.log` and `a1-report.md`.

The focused nine-class host selection passed 97 tests without skips; the profiler suite passed
1,457 executed tests (1,461 discovered, four existing installed-HotSpot-asset skips). Final
verification details and static-analysis blockers are recorded in the implementation report.
At that point independent review and the controller-owned full build/kernel prerequisite assessment
remained pending; these host results alone are not privileged Tier E qualification.

November A1 self-review correction explicitly extends scope to `TierERecoveryReader.kt`, its
existing reader test and the new `TierERecoveryRetirementTest.kt`: distinguish physical reader
termination from failed scanning, retain source/memory and scanner/queue ownership across timeout
or interruption in both final-scan handoff and close, and allow a terminated failed reader to retire.
The existing one-second join bound, experimental opt-in, diagnostics and once-only failed outcome
remain unchanged. This is required cleanup ownership, not an accepted experimental exception.

The recovery correction reproduced premature source/memory retirement in both close and final-scan
handoff with a blocked real reader thread. `stopAndJoin()` now returns physical termination
explicitly, independently of retained `failed` diagnostics. Both session paths retain the reader,
scanner, queue reservation and source until termination; timeout fails finalization, and retries
only retire the outstanding owner. A terminated failed scanner still closes and leaves incomplete
recovery evidence. The new host tests include interrupted join and daemon once-only failed
reporting, not just a default-v1 restriction. Recovery command/output evidence is appended to
`a1-report.md` and retained in `a1-recovery-*.log` before independent review/full gate.
After correction, the required host selection plus the two recovery classes passed 104 tests with
no skips. The whole profiler suite passed 1,463 executed tests (1,467 discovered, four existing
installed-HotSpot-asset skips). Production Detekt passed; test Detekt still reports the identical
16 errors in unchanged test files. No full build or privileged fixture was run by the implementer.

### Final A1 verification

Independent Sol review and scoped re-review approved the A1 fixes; Sol also approved Terra's
mechanical status correction. The final current-dirty-source `MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
./gradlew build --console=plain` passed in 13m 27s (242 tasks: 75 executed, 167 up-to-date).
Profiler host tests discovered 1,471, executed 1,467 and skipped four; rootful tests ran 22 with
no failures, errors or skips. Regular integration discovered 16, executed seven and skipped nine;
fresh-JVM integration discovered 24, executed 19 and skipped five. All selected results had zero
failures or errors. The rootful image was amd64 Temurin 25.0.5+8-LTS,
`sha256:8047b78d7af43aa5fa9dcfd61d5bbdcf47044e0a154221b2e8a3ba28c4fa02e1`, on host kernel
7.2.11-x64v3-xanmod1. Reviews and the build log are retained as local scratch in
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/`, not published receipts.

A1 code/task verification is complete; this issue remains **open** pending orchestrator-managed
review and integration. Skips are not passes, and optional native/HotSpot assets were not all
qualified. This run is not a clean-commit or historical pinned/source acceptance receipt and does
not explain A3's historical 71/72 failure. A2/A4 behavior, APIs and performance were not promoted;
report-only evidence remains `policy_eligibility=false`.

## Related work and boundaries

This is a residual cleanup-ownership gap after [bounded Tier E lifecycle](resolved/issue-20260910-000105-bound-tier-e-retention-and-lifecycle.md), distinct from [collector integration and polling qualification](issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop.md). Preserve the producer-closure evidence contract and polling defaults. Read `../designs/core/notification-liveness-contract.md` before implementation. Declare affected test files in the task scope before execution.

---
## Repository-review expansion — 2026-11-04

[TE-05](../reviews/2026-11-04-repository-review-findings.md#te-05) follows failed retirement
through daemon/runtime/loop: clearing active state and marking closed before fallible finish
defeats the ring reader's correct retained-unmap owner. Require a reachable closing owner,
truthful incomplete terminal, independent cleanup and no replacement acquisition.
[TE-06](../reviews/2026-11-04-repository-review-findings.md#te-06) adds a distinct end-to-end
deadline hole: runtime finish calls metrics under the timed-out poller's monitor after bounded
join. Test runtime finish with interrupt-resistant held poll, returning bounded incomplete
evidence before releasing it, then exact retry/retirement. A service-only interruptible-latch
test is insufficient. These source-confirmed cases extend Plan A A1; no native fault run here.

## Recovery-reader physical-retirement follow-up — 2026-11-05

[Recursive review D-01](../reviews/2026-11-05-state-complexity-review.md)
adds the independent recovery map reader. `TierERecoveryReader.kt:38–42` returns
one Boolean for both physical exit and scan health (`!thread.isAlive && !failed`).
`TierEProfilerSession.kt:693–705,1098–1110` forgets the reader/scanner and releases
queue/source ownership even when false. The scanner can still hold the map FD
captured at `TierEbpfEngine.kt:788–790` and use it at `:799–835` after retirement
closes it at `:1151`. Actual EBADF/FD reuse was not runtime-reproduced here.

- Distinguish `Retired(scanHealth)` from `StillRunning(readerOwner)` stop outcomes.
  Retain the live reader, queue reservation and source/map on timeout or interrupted
  join; do not close/recycle its map, perform an exclusive final scan or permit
  replacement while it can issue map syscalls.
- Failed-but-retired scanning remains evidence-incomplete but may release physical
  resources. Join/interrupt/map I/O remain effects; a pure release decision must
  not conflate evidence health with liveness.
- Deterministically hold an interrupt-resistant scan after resource acquisition.
  Require bounded incomplete delivery and retained ownership before release, then
  actual join and exactly-once outstanding cleanup. Include successful exit,
  failed-but-dead exit, interrupted join and first cleanup failure.
- Exercise the session/runtime boundary, not only the reader outcome. Preserve
  the existing refusal to final-scan without stop proof and existing collector
  timeout/unmap controls. Actual BPF FD lifetime still needs native qualification;
  host checks and the implementation completion gate remain required.

This extends the same physical-ownership issue, not the resolved retention work.
Do not make bounded result delivery wait indefinitely for retirement or let a
replacement hide outstanding ownership.

---
<!-- id: issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c  file: issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md -->

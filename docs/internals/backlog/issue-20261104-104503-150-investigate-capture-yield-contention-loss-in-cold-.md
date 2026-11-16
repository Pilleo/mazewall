---
title: "Investigate capture-yield contention loss in cold Tier E diagnostics"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler-native"]
target_files:
  - "profiler-native/src/agent.cpp"
  - "profiler-native/tests/"
target_symbols:
  - "record_capture_yield"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate capture-yield contention loss in cold Tier E diagnostics

**Context:**
Cycle006 completed both phases but correctly failed selectColdMethods:184dictionary definitions versus183MZCYrecords, missingID85048942395429 has RESOLVEDread0 under readConfiguration. MZCY bindingsopen0=62/read0=119/write0=2; target reports capture-yield-dropped1, stack/scope failures0, all62MZOBopenrecords complete. record_capture_yield drops on try_lock contention and increments the observed counter. This is incompleteobserverdata, not a missingopen or excuse to relax selection.

The reported drop counter does not identify the dropped invocation or prove a historical missing-open cause. Future work is scoped to the native agent and its tests above and requires separate authorization; the failed sample remains unchanged.

**Needed:**
1. Retain failedsample and investigate whether bounded lossless diagnostic-only recording is warranted within existing6MiB budget and nativeoffpath constraints. Independently test concurrentrecording and preserve explicitloss failure semantics; do not hide loss through retries or rewrite historicalsample.

**Released step1 scope (2026-11-05, before executable edits):**
Implement accepted fixed1 two-RMW reservation, fixed 65,536 16-byte slots,
release/acquire commit, terminal CLOSED cut and sticky UINT64_MAX loss. Exact
writable paths are `profiler-native/include/capture_yield_recorder.hpp`,
`profiler-native/src/agent.cpp`, `profiler-native/tests/capture_yield_recorder_test.cpp`,
new `profiler-native/tests/capture_yield_agent_test.cpp`, `profiler-native/CMakeLists.txt`,
this issue, `docs/superpowers/plans/2026-11-03-tier-e-missing-open-discriminator.md`
and `docs/internals/designs/profiler/tier-e-design.md` only. Preserve four callers,
MZCY v1, disabled behavior, invocation IDs and strict read-only consumers. Require
actual-wrapper and actual-writer compiling behavioral REDs, guarded synthetic
post-real-close stream-state failure, real filesystem/unload controls, measured
nonpositive storage delta and compiler/sanitizer/host/Kotlin evidence. A terminal
snapshot is not producer quiescence; active DSO unload is unsupported. Issue stays
open pending independent review and separately released target qualification;
Cycle006 and historical A3 remain unchanged. No new target artifact/sample here.

**Released G3 provenance follow-up (2026-11-05; not acquisition):**
Implement the accepted fixed1 provenance plan against immutable G2 handoff
`018-completed-handoff.json` (SHA-256
`91dfd811a6d61075d2e6a01fab6016d2dfdad967193bca5b1d363161f1cb24df`).
The exact five writable paths are
`profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt`,
its sibling `TierEA4DiagnosticSupportTest.kt`,
`profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt`,
this issue and `docs/superpowers/plans/2026-11-03-tier-e-missing-open-discriminator.md`.
Bind the finite owned/generated/compiler/receipt facts, share exclusive private
copy/check, and revalidate the complete fingerprint and all copies inside the
existing final READY boundary. Require compiling behavioral RED/GREEN, composed
fixtures, focused host quality checks and compiled actual validator/copy against
unchanged real N. Evidence belongs to `a4-fresh-provenance-implementation-001/`.
No native build, full build or target/sample execution is released here. G2
acceptance is additive to the preserved historical failed/BLOCKED receipts; this
issue stays open and A4 FAILED/A3 unresolved remain unchanged.

**Released G3 correction round1 (2026-11-05; review F1–F3):**
Within the existing G3 scope, only `TierEA4DiagnosticSupport.kt`, its sibling
`TierEA4DiagnosticSupportTest.kt` and this issue may change. Require the exact owned
include argument rather than a directory prefix; isolate every required CTest name
with hash-consistent receipt-only substitutions; and exercise the diagnostic-only
missing-source guard with otherwise valid complete native/READY evidence. Preserve
compiling RED/GREEN and guard-disabled sensitivity variants, focused/quality checks
and one final compiled shared-validator/copy/check real-N positive in fresh
`a4-g3-fix1-001/`, against the returned dirty baseline. Original implementation,
authentication, review and native/history evidence stay immutable. No integration,
native, Gradle, default, workload, inventory or budget change; G4/G5 remain withheld.

Round1 evidence in `a4-g3-fix1-001/` demonstrates the six-target include-prefix
counterexample, fixes the exact argument check, and isolates all30 required receipt
names in full/focused scopes plus the diagnostic-only tracer-source guard. Compiled
guard-disabled controls fail6/60/1 respectively; restored focused suites pass89+4
with zero skips. The final compiled shared validator/copy/check passes on unchanged
real N with a fresh34-copy set and full72-entry fingerprint. Existing main quality,
test formatting, integration compilation and wiring pass; additionally invoked
`detektTest` remains blocked by22 existing errors, preserved with exact diagnostics
(four unchanged support lines,18 outside this correction). No suppression or unrelated
repair is authorized. See `a4-g3-fix1-report.md`; independent correction review and
G4/G5 remain outstanding, with this issue open and historical labels unchanged.

**G3 host implementation evidence (2026-11-05; pending independent review):**
The five-path follow-up now binds the fixed 62-member native runtime set, six
receipts and two compiler roles, checks generated semantics and all 30 required
CTest results, and shares exclusive mode-0600 copy/full-map validation at initial,
fingerprint and final locked READY boundaries. Independent synthetic fixtures
separate stale binding from hash-consistent invalid semantics; all 34 retained
copies and each native member have final-freeze controls. Compiling validator,
copy and READY REDs are preserved. Final focused host tests pass 85+4, zero skips;
integration compilation, profiler ktlint/Detekt/SpotBugs and model wiring pass.
The actual compiled final validator/copy/check also passes against real unchanged
N with all six unchanged receipts, retaining its 72-entry map and all 34 private
copies. See `a4-fresh-provenance-implementation-report.md` and numbered evidence
under the existing plan workspace. No native/full build, target JVM, container,
tracer or sample was run; independent review and separately released G4/G5 remain.

**Step1 host implementation (2026-11-05; not target qualification):**
The recorder now owns fixed 16-byte slots, reserves with two bounded atomic RMWs,
publishes each commit with release/acquire visibility and closes once for terminal
serialization. Full/closed rejection sets `capture-yield-dropped=18446744073709551615`
(sticky non-exact positive loss, not a count). Loss or an uncommitted reservation
fails publication; the owner and partial files survive failure. CLOSED also latches
terminal publication attempts, so neither late commit nor repaired filesystem
permits a clean retry. Enabled recording preserves errno without JNI operations.
Successful `written` means publication only, never producer quiescence or safe
active DSO unload. Zero loss and exact closed consumer reconciliation remain
mandatory; consumers and historical evidence are unchanged.

Actual-wrapper overlap and actual-writer synthetic post-real-close error/unload
REDs were compiled before repair; clean control passed. Release host-safe checks
pass 108/108 (all 17 new cases included), USDT-off parity 31/31, and independent
ASan+UBSan and TSan actual-seam schedules 17/17 each. Full-capacity successful wire
and real native decoder are separate from overflow failure; this does not relax
the cold selector's stricter bound. On this host, measured object allocation grows
16 usable bytes; removed global objects total48, but actual mutable `.bss` shrinks
only32 after compiler padding. Slot/output/stream allocation deltas are zero and
the actual production unload frame is unchanged, giving measured net delta -16.
The 6,291,456-byte cap and full 20,928 map-excess allowance are unchanged. These
host results are subject to independent review and target-byte re-audit, not an
archive decision. Frozen Cycle006 remains INCOMPLETE; historical A3 is unresolved.

---
<!-- id: issue-20261104-104503-150-investigate-capture-yield-contention-loss-in-cold-  file: issue-20261104-104503-150-investigate-capture-yield-contention-loss-in-cold-.md -->

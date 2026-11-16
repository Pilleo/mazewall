---
title: "Provide live pre-call stack context before USER_NOTIF authorization"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["platform"]
target_files:
  - "profiler-native/include/live_stack_context_abi.h"
  - "profiler-native/include/live_stack_context.hpp"
  - "profiler-native/src/live_stack_context.cpp"
  - "profiler-native/tests/live_stack_context_test.cpp"
  - "profiler-native/src/agent.cpp"
  - "profiler-native/CMakeLists.txt"
  - "profiler-native/include/invocation_registry.hpp"
  - "profiler-native/include/symbolization_generation.hpp"
  - "platform/src/main/kotlin/io/mazewall/platform/native/NativeEngine.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
  - "docs/internals/designs/enforcer/live-stack-scoping.md"
  - "docs/internals/designs/enforcer/containment-design.md"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/core/notification-liveness-contract.md"
target_symbols:
  - "InvocationScope"
  - "NativeEngine"
open_questions: false
---

# 🟡 [Severity: HIGH]: Provide live pre-call stack context before USER_NOTIF authorization

**Context:**
Seccomp runs before trace_sys_enter, so a stopped USER_NOTIF syscall cannot supply its own Tier E event. Existing terminal dictionaries and the JVM-side Thread.getStackTrace path do not provide a bounded, fresh pre-call authorization handoff. Implement the scoped native snapshot foundation described by the Tier E improvement roadmap; profiling evidence remains policy-ineligible.

**Needed:**
Released T4 private-preparation host continuation (2026-11-04), declared before executable edits:
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`profiler-native/src/live_stack_jni.cpp`, `profiler-native/include/live_stack_jni.hpp`,
`profiler-native/tests/LiveStackContextWorkload.java`, `profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/CMakeLists.txt`, new `profiler-native/tests/LiveRetransformAgent.java`,
new `profiler-native/tests/live-retransform-agent.mf`, this issue and
`issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`.
Original ten-path baseline and 100 protected sources remain binding; no agent.cpp/core/ABI/adapter changes.
Evidence/report: roadmap `b2-private-preparation-002/` and `b2-private-preparation-continuation-report.md`.
Host implementation only; final source review, target measurement/affected-byte audit and qualification remain pending.
Continuation remains incomplete: actual retained JVMTI table-owner substitution has compiled RED/GREEN;
focused host 42/42 and host-safe 91/91 pass. Host preparation maps additionally contain
libjimage/libmanagement/libmanagement_ext/libsimdsort/libzip outside the accepted NEEDED graph.
Pinned-source/artifact/initialization reconciliation or exact nonreach is required before expanding
the fixed participant set; host mapping observations are not pinned-target evidence or an allowlist.
See the continuation report and original receipts; the full checker/fixture remains pending.

Released T6 host-adapter partition (2026-11-04), declared before executable edits:
`platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt`,
`platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt`,
`platform/src/test/kotlin/io/mazewall/ffi/internal/RealNativeStackContextsTest.kt`,
`platform/src/test/resources/abi/layout_oracle.c`, `platform/build.gradle.kts`
(existing oracle include/input wiring only),
`src/sharedTest/kotlin/io/mazewall/MockNativeStackContexts.kt`,
`docs/internals/designs/enforcer/live-stack-scoping.md` (pre-§1 status only),
this issue and `issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`.
Host evidence/report: roadmap `b3-adapter-host-001/` and `b3-adapter-host-report.md`.
No native launch, qualification, integration/kernel suite or accepted-interface change.
Host adapter evidence: 24 adapter tests, focused capability/legacy 37/37 and
platform host 468/468, zero failures/skips; unit/coverage, owning static/lint and
platform ABI checks pass. Actual-header C oracle is exercised. The explicit factory,
captured capability/binding lifetime, exact decoding and retained cleanup path are
implemented; native core remains lifecycle authority. See `b3-adapter-host-report.md`
and `b3-adapter-host-001/` in the roadmap workspace. This does not close T6/B3,
T4 qualification, final distribution or integration gates; this issue stays open.
The bounded host-quality refactor removes all 203 adapter warnings through cached
named-layout metadata, named wire values and cohesive exact validators; no tests,
lint policies, baselines or suppressions changed. Fresh focused 37/37, host 468/468
and architecture 37/37 checks passed. Its then-blocking SyscallInvoker count was
subsequently resolved by the independently reviewed private errno relocation;
the current host root warning gate passes, with no invoker edits in this fix.
Independent review M1 is corrected in shared poison handling: each malformed
cleanup retains its own cause while the first corruption/owner and single automatic
invalidation remain unchanged. Compiled RED/GREEN and fresh focused 38/38 plus host
469/469 tests cover malformed A→B→C and same-owner Pending→Complete cleanup.
See roadmap `b3-adapter-host-fix1-report.md` and `b3-adapter-host-fix1-001/`.
Original reports/evidence remain immutable; independent scoped re-review and runtime
qualification remain pending. This issue stays open.

Released T6 legacy-binary gate scope (2026-11-04), before executable edits:
`platform/src/test/kotlin/io/mazewall/platform/native/NativeStackContextCompatibilityTest.kt`,
`platform/src/test/resources/compatibility/LegacyNativeEngine.kt`,
`platform/src/test/resources/compatibility/legacy-native-engine.jar`,
`platform/src/test/resources/compatibility/legacy-native-engine-provenance.txt`,
this issue and `issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`.
This is a compatibility test only; T6 adapter, T4 qualification and integration remain pending.
Legacy gate host evidence: focused 3/3 and platform 444/444, no failures or skips;
static checks and platform ABI check pass. See roadmap `b3-legacy-report.md`.

Released T5 / B3a scope (2026-11-04), declared before executable edits:
`platform/src/main/kotlin/io/mazewall/platform/native/NativeStackContexts.kt`,
`platform/src/main/kotlin/io/mazewall/platform/native/LinuxNative.kt`,
`platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt`,
`platform/src/test/kotlin/io/mazewall/platform/native/NativeStackContextsTest.kt`,
`platform/src/test/kotlin/io/mazewall/NativeRuntimeSnapshotTest.kt`,
`enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`, this issue,
`issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`,
and bounded pre-§1 evidence in `docs/internals/designs/enforcer/live-stack-scoping.md`.
This unit supplies internal captured-engine types only; the real capability stays
Unsupported until T6. Native/JNI sources, NativeEngine ABI and frozen requirements
remain unchanged. Baseline `b3-types-base/`, evidence `b3-types-001/` in the roadmap
workspace; both issues remain open, with no target qualification claimed.

T5 checkpoint: captured-provider compiled RED retained; final focused host 12/12,
unfiltered platform host 443/443 and matching enforcer architecture 37/37 pass,
zero failures/skips. Owning static and ABI checks pass; NativeEngine source/class
and frozen native/JNI inputs are unchanged. Real capability remains Unsupported.
The platform `check` dry run schedules prohibited kernel suites; host `unitCheck`
and explicit static checks are recorded instead, pending controller clarification.
See roadmap `b3-types-report.md`; T6 adapter, target qualification and integration
remain pending, and this issue stays open.

Released T4 G1 prerequisite correction (2026-11-04), before executable edits:
exact inactive JNI invocation of the registered cached probe before helper publication,
with false/no-exception success and fail-closed exception/state controls. Scope is
`profiler-native/src/live_stack_jni.cpp`, `profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`, `profiler-native/CMakeLists.txt`,
these two existing live-stack/reentry issues and pre-§1 design status only.
Baseline `b2-probe-preparation-base/`; fresh host evidence `b2-probe-preparation-001/`.
Guard/core authority and per-activation witnesses stay unchanged. No qualification,
container execution or G2/G3/G4 completion is authorized; both issues remain open.

G1 checkpoint: compiled invocation RED retained; final focused host CTest 41/41
and selected host-safe suite 90/90 pass without failures/skips. Exact cached JNI
probe now runs inactive before publication, requiring false/no exception. Real
Only/Shared startup-failure controls and embedded-JVM exact throwable/unpublished
state checks pass; guard/core and fresh per-activation witness semantics are unchanged.
Independent T2/T3 spec/code-security reviews passed (one nonblocking minor each);
G1 independent review, G2/G3/G4, T4 qualification and integration remain pending.
See roadmap `b2-probe-preparation-report.md`; this issue remains open.

Released T3 / B2.3a scope, declared before executable edits (2026-11-04):
per-worker guard, actual-thread preparation and independent shared acquisition in
`profiler-native/src/agent.cpp`, `profiler-native/CMakeLists.txt`,
`profiler-native/include/live_stack_jni.hpp`, `profiler-native/src/live_stack_jni.cpp`,
`profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`, and
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`;
only the hidden read-only `probe_eligible()` query and covering tests in
`profiler-native/include/live_stack_context.hpp`, `profiler-native/src/live_stack_context.cpp`,
and `profiler-native/tests/live_stack_context_test.cpp`. Documentation scope is
this issue, the owning reentry issue and bounded pre-§1 status in
`docs/internals/designs/enforcer/live-stack-scoping.md`. The exact 13-path baseline
is `b2-guard-base/`; release is bound by `b2-guard-001/controller-release.json`.
Sole writer/executor, current checkout, fresh numbered CREATE_NEW evidence in
`b2-guard-001/` and `b2-guard-target-001/`, builds `/tmp/opencode/b2-guard-001/`.
Core lifecycle/ABI/authority, frozen evidence and other sources remain unchanged;
no Git writes, worktrees, agents, dependencies, cleanup or broader privilege.
T4, ordinary support, resource permission and independent review remain pending.

T3 implementation checkpoint: final pinned-target candidate run007 passes focused
57/57 and unfiltered native 106/106, zero failures/skips; host-safe checks pass
32/32 and the final unchanged core passes focused TSan and ASan/UBSan 1/1 each.
Actual per-worker setup, only/shared helper acknowledgment, complete Available,
single acquisition, virtual refusal, enable failure, exact JNI pre-body throwable
and physical retirement are covered; broader outcome controls use explicit
component fakes. Platform compile/static tasks succeed UP-TO-DATE. Evidence and
all failures are in roadmap `b2-guard-report.md`. Independent review and T4/B3/B4/B6
qualification/integration remain pending; this issue stays open and no permission
or ordinary support is inferred.

1. Execute B1–B3 of the [owned stack-scoping plan](../../superpowers/plans/2026-11-03-tier-e-owned-stack-scoping.md): specify a bounded task-, invocation-, and code-generation-bound live snapshot bridge, independent of ring delivery and terminal dictionaries.
2. Reuse pre-call capture in `InvocationScope`, publish only complete current snapshots, and expose a nonblocking typed bridge behind the platform native boundary. Live reads must not walk the stopped thread, resolve JVMTI symbols or read terminal files.
3. Prove invalidation on capture failure, callback/nesting, task transition, generation change and retirement; retain ownership through failed cleanup. Preserve diagnostic protocols and keep enforcement independent of the profiler JVM runtime.
4. Add native and platform regression tests, a no-collector real-agent smoke, ABI/layout and dependency checks. Declare new header/adapter/test paths in the task scope before execution.

## Scope and acceptance

### Released T2 / B2.2b scope (before executable edits, 2026-11-04)

Sole writer/executor in the current checkout against `b2-enrollment-base/`.
Exactly ten released paths: `profiler-native/src/agent.cpp`,
`profiler-native/CMakeLists.txt`, `profiler-native/include/live_stack_jni.hpp`,
`profiler-native/src/live_stack_jni.cpp`,
`profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`,
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`docs/internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`,
`docs/internals/backlog/issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`,
and `docs/internals/designs/enforcer/live-stack-scoping.md` (bounded status/evidence only).
Checked deployment, actual JNI enrollment and Seal prerequisites only; no core/ABI,
publication/guard, worker readiness, public API or ordinary support qualification.
Fresh evidence: roadmap `b2-enrollment-001/`, `b2-enrollment-target-001/` and
`b2-enrollment-report.md`; host build `/tmp/opencode/b2-enrollment-001`.
All other sources, owners and historical evidence remain immutable; both issues
remain open. No nested agents, Git writes, worktrees, dependencies or cleanup.

T2 context request resolved by controller: launch comparison uses canonical
effective VM arguments and ordered classpath plus all fixed participant/artifact/
helper/mapping/target checks. Application entry point/arguments stay in complete
test/qualification launch receipts, not new identity fields or overloaded evidence
IDs. Exact T4 workload/configuration and trusted-native closure remain binding.
Implementation resumes under the same ten-path release; history and evidence
remain in `b2-enrollment-report.md`. No runtime/provenance blocker was established.

Resumed T2 now has a concrete target blocker: fresh bounded run005 compiled but
actual self `map_files` descriptor opens returned EPERM under the released
non-root/cap-drop-ALL command. Run004 exposed the documented Btrfs maps/stat
device-domain mismatch; raw device equality is invalid, and pathname/hash-only
fallback is not permitted. Production deployment checks and real measured test
scaffolding are partial/unreviewed; enrollment/Seal remain unimplemented and
Unsupported. No positive, qualification or completion is claimed. Controller
must release an actual mapped-object proof mechanism/command before affected
target work resumes; all attempts and diagnosis remain retained in the report.

Controller released the T2 resume supplement: the same ten paths/original baseline,
fresh `b2-enrollment-002/` and `b2-enrollment-target-002/`, UID0 with only
CAP_CHECKPOINT_RESTORE for bounded test-owned runs. Frozen native005 artifacts now
pass genuine deployment; actual enrollment reaches its refusing JNI body (RED).
Resume real enrollment and closed deployment/move-only ownership/race tests under
that release. No ordinary unprivileged support, qualification or completed T2 is
implied; historical blockers/attempts stay immutable and this issue stays open.

T2 implementation/self-review checkpoint: current hardened source passes target
run014 focused 23/23 and unfiltered native 93/93, zero failures/skips; host startup/
refusal 9/9 and platform compile/static command pass. Actual info→Reserve→enroll→
Seal, two-loader tokens, rollback/throwable identity, retained JNI references,
retryable cleanup/reservations and rebind/replacement races are covered. Closed
deployment ownership, one-shot initialization and atomic helper-cache publication
are implemented without core/ABI changes. Mapping proof uses the explicitly
released UID0/only-bit40 test envelope; unprivileged support is not established.
All earlier failures, storage recovery and current source/artifact hashes are in
the appended `b2-enrollment-report.md`. Independent T2 review and all T3+ gates
remain pending; probe/worker preparation still refuse, no Available or permission
is claimed, and this broader issue stays open.

### Released T1 / B2.2a scope (before executable edits, 2026-11-04)

The sole writer works in the current checkout against the verified
`b2-startup-base/` dirty-checkout baseline. Exactly these ten paths are owned:
`profiler-native/src/agent.cpp`, `profiler-native/CMakeLists.txt`,
`profiler-native/include/live_stack_jni.hpp`, `profiler-native/src/live_stack_jni.cpp`,
`profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`,
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`docs/internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`,
`docs/internals/backlog/issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`,
and `docs/internals/designs/enforcer/live-stack-scoping.md` (status/evidence only).
This unit supplies opt-in startup and exact helper setup only; setup JNI bodies
explicitly refuse until T2/T3, probe returns false, and core remains Unsupported.
No enrollment, workers, Available, ABI/lifecycle changes or qualification. Both
issues remain open, index/protected sources/historical evidence stay unchanged.
Fresh host evidence belongs to `b2-startup-001/` in the roadmap workspace and
`/tmp/opencode/b2-startup-001`; independent review follows self-review.

### Released documentation amendment scope (2026-11-04)

This documentation-only unit edits exactly these eight documents: `docs/internals/designs/enforcer/live-stack-scoping.md`, `docs/internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`, `docs/internals/backlog/issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`, `docs/superpowers/plans/2026-11-03-tier-e-owned-stack-scoping.md`, `docs/superpowers/plans/2026-11-03-tier-e-improvement-roadmap.md`, `docs/internals/designs/enforcer/containment-design.md`, `docs/internals/designs/profiler/tier-e-design.md`, and `docs/internals/designs/core/notification-liveness-contract.md`. It changes no implementation, tests, build wiring, index or other product documentation. The historical B2.1 scope below is unchanged.

### Released B2.1 scope (before source edits)

This serial unit changes only the four native live-stack files listed above,
`profiler-native/CMakeLists.txt`, this issue, and
`docs/internals/designs/enforcer/live-stack-scoping.md` (implementation evidence
only). It delivers bounded inert native state, the frozen v1 ABI, lifecycle,
publication/copy and synthetic native concurrency tests. JNI/Class enrollment,
agent startup/proxies, platform adapters and enforcement remain later units.
Existing diagnostic sources/workloads and pinned acquisition artifacts are preserved.
Native focused/full CTest and separate sanitizer builds supply core evidence;
the controller owns the later Gradle completion gate. Original requirements and
open status remain unchanged; no live route or application access is qualified.

This issue delivers the live-context foundation, not application authorization.
Resource-safe brokering, API integration and complete enforcement qualification
are separately scoped B4–B8 deliveries. No public access is enabled by completing
this issue. See the [roadmap](../../superpowers/plans/2026-11-03-tier-e-improvement-roadmap.md)
for dependencies and the trusted-JVM limitation.

- Snapshot reads are bounded and return explicit unavailable/stale outcomes.
- A blocked native call holds no lock needed by its snapshot reader.
- TID reuse, wrong task/installation/code generation and truncated capture cannot
  produce available authority-bearing context.
- Collector absence, profiling selection and ring loss cannot disable mandatory
  live capture. Existing Tier E attribution regressions remain passing.
- Focused CTest/platform/JVM checks and the repository completion gate pass with
  retained source/configuration evidence; no current runtime result is claimed.

## Ordering evidence

Linux v6.12 `kernel/entry/common.c` invokes `__secure_computing()` before
`trace_sys_enter()` in syscall entry. Thus USER_NOTIF cannot wait for the blocked
syscall's own tracepoint event. Source:
https://github.com/torvalds/linux/blob/v6.12/kernel/entry/common.c

This is a design prerequisite observed by source inspection, not a reproduction
of an implemented Tier E enforcement bug. Qualify other architecture/kernel paths
before claiming support.

## B1 design evidence and remaining gates

The [canonical live stack contract](../designs/enforcer/live-stack-scoping.md)
was published as a proposal, not implementation or qualification evidence. Its reviewed design is frozen for
implementation review; this documentation is not executable qualification and
does not unblock A4. Remaining B2.2/B2.3/B3 implementation, real-agent and compatibility evidence,
and orchestrator integration remain outstanding. B4 resource confinement and B7
qualification are independent gates; an available snapshot grants no permission.
This issue remains open, and its original requirements remain in force.

## T1 / B2.2a startup/helper evidence (2026-11-04)

The declared ten-path unit supplies real opt-in only/shared startup, ordered
capability verification and exact loader/module/helper registration. Diagnostic
default behavior remains unchanged; only mode owns no diagnostic recorder or
publisher. Preparation natives refuse Unsupported and probe returns false:
no core initialize, enrollment, workers, Available or support qualification.
Final focused host CTest passed 8/8; the normal native suite passed 70/70 with
zero skips/failures. Actual platform Java/Kotlin compilation, SpotBugs and owning
KtLint checks passed. Compiled behavioral REDs, genuine JVM negative causes and
non-admission assertions are retained with command/exit/hash evidence under
roadmap `b2-startup-001/`; `b2-startup-report.md` records self-review and handoff.
Independent review and the controller's final serial-integration gate remain
pending. This issue remains open; original requirements and all later gates stand.

## B2.1 bounded-core evidence (2026-11-04)

The seven-path unit now supplies inert native v1 state/ABI/lifecycle/publication/copy
and synthetic owner/capture tests, without agent/proxy/JNI/platform/enforcer edits.
Final native focused CTest passed 13/13; the normal native suite passed 62/62 with
zero skips/failures. The focused deterministic concurrency case passed separately
under TSan (1/1) and ASan/UBSan (1/1). Initial behavioral RED, registration-rollback
RED/GREEN, intermediate failures, final logs and hashes are retained in the roadmap
workspace's `b2-core-*` artifacts; no earlier failed evidence was removed.

The controller's frozen fullgate001 receipt records build exit 0, 426 fresh tests
(17 skipped, no failures/errors), including 26 rootful tests with no skips; three
child-shutdown ENOSYS stacks remain under issue 20261003-131111-752. This satisfies
the B2.1 native-core-only completion gate, not clean retirement or qualification.
The independently reviewed [canonical re-entry amendment](../designs/enforcer/live-stack-scoping.md#41-reviewed-re-entry-amendment-and-qualification-gate-2026-11-04)
replaces the original callback-free assumption with a guarded closed envelope and
explicit pre-original exception outcomes. Candidate tests use the real path; ordinary
support requires completed matching qualification. Startup/Class enrollment, guard,
proxy/FFM, installed-artifact/route proof, external fixture, B4/B6 and qualification
remain open. No application access or runtime support is granted.

---
<!-- id: issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no  file: issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md -->

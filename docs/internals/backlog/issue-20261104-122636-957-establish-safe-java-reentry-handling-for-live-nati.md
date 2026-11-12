---
title: "Establish safe Java reentry handling for live native open snapshots"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler-native", "platform"]
target_files:
  - "profiler-native/src/agent.cpp"
  - "profiler-native/tests/"
  - "platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java"
  - "docs/internals/designs/enforcer/live-stack-scoping.md"
target_symbols:
  - "InvocationScope"
open_questions: false
---

# 🟡 [Severity: HIGH]: Establish safe Java reentry handling for live native open snapshots

**Context:**
The [live snapshot foundation](issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md) does not grant application access: B2.1 is an inert native core with synthetic tests. The original frozen B1 excluded callback-capable originals; the independently reviewed [amendment](../designs/enforcer/live-stack-scoping.md#41-reviewed-re-entry-amendment-and-qualification-gate-2026-11-04) now specifies a bounded guard and closed envelope. This issue records one callback/admission gap, not a production exploit claim or a general instrumentation redesign.

In candidate Temurin source commit `7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c`, [open0 and openat0](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c#L502-L533) call `throwUnixException` on error; it calls `JNU_NewObjectByName`, whose [NewObjectV](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/java.base/share/native/libjava/jni_util.c#L368-L396) executes a Java constructor before the original returns. Warming or success-only tests cannot prove whole-original callback freedom.

Plain MethodEntry poisoning misses explicit [HotSpot event-hiding contexts](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/hotspot/share/prims/jvmtiExport.cpp#L1849-L1886). A fresh exact native-helper witness can reject inherited hiding, but a co-agent's [GetThreadGroupChildren](https://github.com/adoptium/jdk25u/blob/7ec19fb1eda7e42498b7ee4ebde29f77aa961a1c/src/hotspot/share/prims/jvmtiEnvBase.cpp#L861-L890) can introduce a later hidden Java upcall. These are inspected-source findings, not installed-binary or syscall-route qualification. The reviewed design is not an implemented guard or readiness.

Research evidence is retained in `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b2-leaf-binding-evidence.md`, `b2-java-reentry-feasibility.md`, and `b2-reentry-admission-design-check.md` in that same directory. Preserve their historical diagnostics and failures.

**Needed:**
Released T4 private-preparation host continuation (2026-11-04), declared before executable edits:
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`profiler-native/src/live_stack_jni.cpp`, `profiler-native/include/live_stack_jni.hpp`,
`profiler-native/tests/LiveStackContextWorkload.java`, `profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/CMakeLists.txt`, new `profiler-native/tests/LiveRetransformAgent.java`,
new `profiler-native/tests/live-retransform-agent.mf`, this issue and
`issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`.
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
this issue and `issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`.
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
this issue and `issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`.
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
`issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`,
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
this issue, the owning live-stack issue and bounded pre-§1 status in
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

Released T2 / B2.2b scope, declared before executable edits (2026-11-04): exactly
`profiler-native/src/agent.cpp`, `profiler-native/CMakeLists.txt`,
`profiler-native/include/live_stack_jni.hpp`, `profiler-native/src/live_stack_jni.cpp`,
`profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`,
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`docs/internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`,
`docs/internals/backlog/issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`,
and `docs/internals/designs/enforcer/live-stack-scoping.md` (bounded status/evidence only).
Sole writer/executor/current checkout; baseline `b2-enrollment-base/`. Checked
deployment, actual JNI enrollment and Seal prerequisites only; no core/ABI,
publication/guard, worker readiness, public API or ordinary support qualification.
Fresh evidence: roadmap `b2-enrollment-001/`, `b2-enrollment-target-001/` and
`b2-enrollment-report.md`; host build `/tmp/opencode/b2-enrollment-001`.
Other sources, owners and historical evidence stay immutable; both issues stay
open. No nested agents, Git writes, worktrees, dependencies or cleanup.

T2 launch-input context is resolved by controller: compare canonical effective VM
arguments and ordered classpath with all fixed participant/artifact/helper/mapping/
target checks. Application entry point/arguments stay in launch receipts, not new
identity fields or overloaded evidence IDs. Exact T4 workload/configuration and
trusted-native closure remain binding. Resume the same ten-path implementation;
retain request/history in roadmap `b2-enrollment-report.md`. No runtime/provenance
blocker was established and no deployment/enrollment success is yet claimed.

Resumption found an actual blocker in fresh target run005: self `map_files`
descriptor open returns EPERM with the released user 1000:1000/cap-drop ALL.
Run004 identifies the existing Btrfs maps/stat device-domain mismatch; no raw
device or pathname/hash-only fallback may manufacture agreement. Checked
deployment and measured target test scaffolding are partial/unreviewed; no
deployment GREEN, enrollment/Seal implementation, guard or readiness is claimed.
Affected target work is stopped pending a controller-released mapped-object proof
mechanism/command. Preserve all attempts and report history; this issue stays open.

Controller's resume supplement retains exactly the ten declared paths/original
baseline and releases fresh evidence parents plus bounded UID0/only-bit40 mapping
proof tests. Frozen native005 artifacts now pass actual deployment and reach
actual enroll0 refusal (enrollment RED). Implement actual enrollment plus closed
deployment/ownership and deterministic race/rollback tests under this same scope.
No ordinary support, guard, qualification or task completion is inferred; preserve
all old failures and append new evidence to the report. This issue remains open.

Current T2 implementation/self-review checkpoint: run014 verifies the helper-cache
hardening and real deployment/enrollment/Seal prerequisites, focused 23/23 and
unfiltered native 93/93, zero failures/skips. Host startup/refusal checks pass 9/9;
platform compile/static command succeeds with unchanged up-to-date inputs. Actual
Class/loader/module identity, rollback/exact throwable, GC retention, cleanup retry,
reservation ownership and real bind/replacement races are covered. Only the
explicit UID0/only-CAP_CHECKPOINT_RESTORE test envelope supplies mapped-object proof
here; no ordinary support, guard, worker readiness or Available is qualified.
All failed attempts and storage/resumption history remain in the appended report.
Independent T2 review, T3/T4 re-entry/guard qualification and later gates remain
required; this issue stays open and original requirements are unchanged.

Released T1 / B2.2a scope, declared before executable edits (2026-11-04): exactly
`profiler-native/src/agent.cpp`, `profiler-native/CMakeLists.txt`,
`profiler-native/include/live_stack_jni.hpp`, `profiler-native/src/live_stack_jni.cpp`,
`profiler-native/tests/live_stack_agent_test.cpp`,
`profiler-native/tests/LiveStackContextWorkload.java`,
`platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java`,
`docs/internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md`,
`docs/internals/backlog/issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md`,
and `docs/internals/designs/enforcer/live-stack-scoping.md` (status/evidence only).
Verified dirty-checkout baseline: `b2-startup-base/`. One writer/current checkout;
opt-in startup and actual helper setup only, refusing JNI bodies pending T2/T3,
false probe and Unsupported core. No enrollment, workers, Available, guard,
ABI/lifecycle changes or qualification; both issues stay open and index, protected
sources and historical evidence stay frozen. Fresh host checks are retained in
`b2-startup-001/` and `/tmp/opencode/b2-startup-001`; independent review follows.

T1 implementation evidence: real only/shared startup and exact helper setup now
exist; probe returns false and preparation natives explicitly refuse Unsupported
until T2/T3. No guard, deployment agreement, enrollment, workers or Available.
Final host focused CTest passed 8/8; normal native CTest passed 70/70, zero skips
or failures. Actual platform Java/Kotlin compilation, SpotBugs and owning KtLint
checks passed. REDs, negative JVM causes/non-admission and self-review are retained
under roadmap `b2-startup-001/` and `b2-startup-report.md`. These are unqualified
host startup results only. Independent task review and the controller's final
serial-integration gate remain pending; this issue and all requirements stay open.

1. Implement and independently verify the reviewed callback-handling and qualified-deployment contract under separately declared scope, or retain Unsupported. Design acceptance does not qualify source artifacts or runtime.
2. Close installed-artifact, binding, syscall-route and deployed call-chain proof, including co-agent and hidden-upcall compatibility; unproved routes and incompatible participants remain unsupported.
3. Discriminate actual JNI error and initialization paths, inherited hiding, stale, duplicate and wrong-thread witnesses, and refusal of incompatible co-agents. Preserve before-Java poisoning and no parent restoration, actual executed-original outcomes and explicit pre-original exceptions, native bounds, one-gate copy and lifetime, and failure and permission behavior. Final guard-chain, matching libinstrument artifact and genuine external-retransformation controls remain mandatory.

---
<!-- id: issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati  file: issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md -->

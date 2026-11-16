---
title: "B6 bind authenticated listener authorization to exact Live worker task"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "enforcer"
  - "platform"
# Target union covers the accepted handshake and declared observation scope; membership is not qualification by Full003.
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/LiveValidationTransport.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperControl.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperRelay.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/ConfinedHelperChannel.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/LiveListenerHandler.kt"
  # Declared new observation protocol path; absent from the Full003 frozen inventory and not qualified by that gate.
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/LiveObservationProtocol.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/OwnedStackScopingBootstrap.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperControlTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/internal/supervisor/CoordinatorCleanupTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/internal/supervisor/LiveDaemonHandoffTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/internal/supervisor/LiveInstallationLifetimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingTestFixture.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  # Captured-reader prerequisite Stage 1 only; this does not release the Needed 3 remainder.
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/TraceeMemoryReader.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/memory/TraceeMemoryReaderTest.kt"
target_symbols:
  - "OwnedDomain"
  - "LiveValidationTransport"
  - "OwnedInvocation"
  - "ContainedExecutors"
  - "ConfinedHelperControl"
  - "ConfinedHelperRelay"
  - "ConfinedHelperChannel"
  - "SupervisorInstaller"
  - "LiveListenerHandler"
  - "LiveObservationProtocol"
  - "SupervisorDaemonEngine"
  - "SupervisorSeccompNotifInstaller"
  - "OwnedStackScopingBootstrap"
  - "SeccompDaemonEngine"
  - "OwnedStackScopingTestFixture"
  - "ArchitectureTest"
  - "TraceeMemoryReader"
  - "TraceeMemoryReaderTest"
open_questions: true
---

# 🟡 [Severity: HIGH]: B6 bind authenticated listener authorization to exact Live worker task

**Context:**
The accepted B5 checkpoints establish bounded helper ownership and structural worker/task lifecycle, but production Live authorization remains closed. Issue 885 covers D4 worker-task lifecycle and B6 refusal; it does not implement the listener dispatcher. Root accepted the bounded first connection/listener handshake and the bounded task/corridor observation, both with production guards closed; see the [handshake evidence](../evidence/tier-e-b6-listener-handshake-20261114.md), [observation evidence](../evidence/tier-e-b6-task-corridor-observation-20261114.md), and observation controller decision (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-task-observation-implementation001/controller-decision-20261114-001.md`). This does not accept whole B6 or authorize effects or production Live activation. The broader B6 design in `.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-listener-task-authorization-plan-20261114-001.md` remains a reviewed proposal pending the separate resource/effect gate. The target paths above union handshake and task-observation scope; membership is not a claim that every listed file was independently changed or qualified by one gate.

The authority chain follows the original `OwnedDomain` through `ContainedExecutors` and `SupervisorInstaller` into the private `LiveInstallationLifetime` and its original listener/session. This is not the Legacy `JVMValidationListener` seam and Live handling cannot fall back to Legacy inspection. Authenticate the actual validation socket peer against the original manager/domain before passing `SCM_RIGHTS`; a control-channel Ready/ACK, request field, or one-way peer credential alone is not proof of the accepted Setup binding. The daemon-side Live prelude must verify the parent and exact accepted Setup binding, associate the one-shot listener with that original session, and preserve the original admission record, socket, and listener. Legacy mode must remain unchanged and must never receive a Live decision or effect.

**Needed:**
1. **Accepted bounded connection/listener binding (2026-11-14):** Root accepted this first increment under `ACCEPT_BOUNDED_LISTENER_HANDSHAKE_WITH_PRODUCTION_GUARDS_CLOSED`; see the [portable evidence](../evidence/tier-e-b6-listener-handshake-20261114.md) and controller decision (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-listener-handshake-controller-decision-20261114-001.md`). The accepted slice authenticates and binds the original-domain/worker connection, Live prelude and one-shot listener admission, with bounded retirement. Full003 passed: 4,784 tests, 45 skips, zero failures/errors; preserve the full scoped gate details and limitations in evidence. Tests use a synthetic domain/mock native worker and regular-file SCM surrogate; no real kernel notification, native bootstrap, custody, or binding-to-raw-syscall route is established. Both production guards remain closed. This acceptance adds no task-observation/effect authority and does not complete B6.
2. **Accepted bounded task/corridor observation (2026-11-14):** Root accepted `ACCEPT_BOUNDED_TASK_CORRIDOR_OBSERVATION_WITH_PRODUCTION_GUARDS_CLOSED`; see the [portable evidence](../evidence/tier-e-b6-task-corridor-observation-20261114.md) and controller decision (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-task-observation-implementation001/controller-decision-20261114-001.md`). The accepted observation is exact-task/corridor only and returns through the canonical EPERM reply owner; it confers no permission, resource access or callback/effect authority and establishes no binding-to-syscall route. Static005 semantic checks and fresh post-format lint/compile passed; the five affected classes had 273 actual test passes, while their original identity-collector packet remains failed with exit 95 for six omitted parameterized cases. The owning unit run passed 1,631 tests, two skips, zero failures/errors, coverage 88,828/108,115. The full gate passed with 4,819 tests, 45 skips, zero failures/errors and coverage recorded in evidence. The 12-case packet's test-lint failure and all earlier failed attempts remain historical. Issue 752 stays UNKNOWN. Both production guards remain closed; no READ, ADDFD, CONTINUE, NativeEngine/public API/dependency change, or native/public activation is released.
3. **OPEN — captured-reader prerequisite Stage 1 released; remaining scope unreleased:** Root released only Stage 1 under the captured-reader controller release (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-captured-reader-stage1-release-20261114-001.md`). This permits the bounded TraceeMemoryReader factory/reader prerequisite in the two platform paths above; it is not completion of Needed 3 or B6. Stage 2 actual Live operation capture, asynchronous ownership, path-byte handling, route/resource policy, and any effects remain unreleased pending concrete design and security review. No READ, ADDFD, CONTINUE, callback, permission/effect authority, NativeEngine/public API/dependency change, native/public activation, or production guard movement is released. Needed 3 and issue 094 remain OPEN.
4. For each bounded increment, run focused tests, then owning static/unit checks, then one coherent full gate. Preserve a Legacy control proving unchanged behavior. Synthetic already-installed physical-worker tests are not protected-native or real-helper-process qualification.

**Security constraints:**
- Keep the production Live guard closed and preserve Legacy behavior; fail closed on absent, malformed, mismatched, stale, replayed, or uncertain binding.
- No caller-selected domain/task authority, cached verdict, PID-only authority, socket-I/O/callback/copy under owner gates, or effect authority from observation.
- Preserve exact original owner/session/listener associations and revoke leases before replacement; keep all callback and copy work outside gates.
- Do not add public Native APIs or dependencies. Any required ABI/native boundary change needs separate explicit approval.

---
<!-- id: issue-20261114-023757-094-b6-bind-authenticated-listener-authorization-to-ex  file: issue-20261114-023757-094-b6-bind-authenticated-listener-authorization-to-ex.md -->


**Conditional receiver-retirement verification hold (source-level, not a demonstrated runtime failure):** The subsequent source review identifies a conditional frontier: if a captured stack copy on the original `LiveInstallationLifetime` receiver remains in progress beyond its five-second close wait, receipt close records failure and domain failure handling may let the application worker exit. Current retirement gates wait for application workers, not the receiver's completed latch. If controlled native retirement reports `Complete`, internal `retired()` and native/resource release may proceed while that receiver remains live. Recorded cleanup failure still makes public drain Incomplete and blocks a fresh generation; no false public `DrainReport.Complete`, actual native blockage, or memory damage is established. This is not evidence that the condition occurred at runtime. See the receiver-retirement hold (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-observation-receiver-retirement-hold-20261114-001.md`).

Before any further B6 integration or self-read/producer release, add the authentic deterministic regression using the original receipt/lifetime and controlled first/second-copy barrier: hold the receiver past the real close timeout; verify cleanup failure/public Incomplete while asserting no native worker, installation, helper, or original-resource retirement and no internal `retired()` until that exact receiver exits; then release the same barrier and verify exact-owner settlement without another copy, socket close, or native-operation replay. First obtain an authentic failure. Any production ownership change requires a separate scoped plan review and controller release. The captured-reader Stage 1 platform prerequisite is independent; its success does not resolve this receiver-retirement gate. Both production guards remain closed.


**Captured-reader prerequisite status update (2026-11-14):** Root accepted the bounded internal captured-reader prerequisite with explicit verification limits; this is not Stage 2, Needed 3 completion, or B6 completion. The factory uses the supplied `NativeEngine`'s injected memory and filesystem readers; ambient `real()`/`Real` and Legacy behavior remain unchanged. Focused reader tests passed 4/4. The owning platform verification had Gradle exit 0 and 522 tests with zero skips/failures/errors; platform tests, both lint source sets, Detekt, SpotBugs, Kotlin ABI, and warning ratchet executed. Its wrapper exit 99 / `SOURCE_CHANGED` resulted from the controller-authorized issue 094 documentation write during execution and remains a failed wrapper packet; see the captured-reader controller decision (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-captured-reader-implementation001/controller-decision-20261114-001.md`) and independent platform audit (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-captured-reader-platform-independent-audit-20261114-001.md`). No current-tree full-build claim is made.

The receiver-retirement verification hold above remains the next priority before adding a memory-read producer or releasing self-read/producer behavior. Both production guards remain closed; no READ, ADDFD, CONTINUE, callback/effect authority, native/public activation, baseline relaxation, or broader qualification is released. The captured-reader prerequisite does not resolve the receiver-retirement gate or qualify Needed 3's remaining scope.


**Receiver-retirement regression and scoped implementation release (2026-11-14):** Root accepted diagnostic008 as an authentic bounded component RED and released the specific receiver-retirement ownership implementation in the linked controller decision. The retained test observed the original receiver still alive after the real five-second lifetime close timeout, caller exit, and incomplete drain with failure; the actual domain then reported retired and its prepared-operation owner closed while the receiver remained alive. Mock worker/installation retirement handlers ran, the synthetic relay reap counter advanced, and the exact original helper plan was marked closed. `Plan.close` marks closed before individual pin cleanup, so pin-close success was not established. This was not a full `runWorker`/`InstallationReceipt`, native/kernel, or custody execution; post-release assertions were not reached. Prior diagnostics 005/006 retain their qualified limitations. See the controller production release (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-receiver-retirement-regression001/controller-production-release-20261114-001.md`) and independent audit (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-receiver-retirement-independent-audit-20261114-001.md`).

The released implementation scope is limited to `LiveValidationTransport.kt`, `OwnedDomain.kt`, `SupervisorInstaller.kt`, and `ConfinedHelperRuntimeDrainTest.kt`, with the exact paths and lifecycle constraints in the controller release. This release authorizes implementation; it does not accept that implementation as complete. Needed 3 remains OPEN. Both production guards remain closed; no self-read producer, v2 codec, resource/effect authority, READ, ADDFD, CONTINUE, callback, or production Live activation is released.


## B6 original receiver-retirement ownership — accepted with explicit limits — 2026-11-14

Root accepted `ACCEPT_RECEIVER_RETIREMENT_FIX_WITH_EXPLICIT_LIMITS`; see the [portable evidence](../evidence/tier-e-b6-receiver-retirement-20261114.md), controller decision (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-receiver-retirement-implementation001/controller-decision-20261114-001.md`), and independent full audit (workspace-only artifact: `../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-receiver-retirement-full-independent-audit-20261114-001.md`). The accepted fix closes the specifically identified receiver-retirement frontier: the original actor joins the exact registered receiver outside owner gates after application workers physically stop, and no native/helper/prepared-resource release or retired/drain completion is published until that receiver physically exits. Late exit permits exact-owner settlement without replaying ambiguous socket/native effects.

Full002 passed with 4,823 tests, 45 skips, zero failures/errors; the portable evidence records the complete gate, audit, coverage, and limitations. The earlier owning unit wrapper's formatting failure remains a failed packet and is not relabeled. Component RED008 remains historical evidence; the acceptance does not assert successful per-pin close, full guarded startup, or execution of production `runWorker` through `InstallationReceipt.close`.

This acceptance does not complete Needed 3 or B6. Operation-input provenance, resource/effect policy, and later review remain open. Both production guards remain closed. No self-read producer, v2 codec, effects, READ, ADDFD, CONTINUE, callbacks, or production Live activation is released. Kernel USER_NOTIF/native route, protected custody/deployment, original 20+20, skipped suites, Plan A, B7/B8, issue 752 and other retained qualifications remain open or unqualified.


## Private native proxy-argument prerequisite accepted — 2026-11-14

The bounded [private native argument provenance evidence](../evidence/tier-e-b6-native-argument-provenance-20261114.md) records actual Open0/Openat0 scalar publication and strict hidden matching under issue 368. It binds those proxy inputs to the same private publication; it does not prove kernel register/request provenance, provide a consumer or bridge, authorize a read, grant permission/effects, or complete B6. Issue 094 and Needed 3 remain OPEN for the remaining operation/resource/effect policy and qualification. Both production guards remain closed; no self-read/v2, READ, ADDFD, CONTINUE, callback, or production activation follows.


## Native-only match wrapper prerequisite accepted — 2026-11-14

The [native-only wrapper evidence](../evidence/tier-e-b6-native-match-wrapper-20261114.md) records one additive status-only native ABI export around the accepted hidden matcher. It exposes Match/Unavailable/Malformed only; it does not read memory, establish kernel request/register provenance, grant permission/effects, or provide a platform consumer. Issue 094 and Needed 3 remain OPEN; both production guards remain closed. No platform adapter, JNI/FFM bridge, self-read, READ, ADDFD, CONTINUE, callback, or production activation is released.


The durable [Tier E pause/resume handoff](../evidence/tier-e-pause-handoff-20261114.md) summarizes accepted bounded checkpoints and the remaining B6 gates for clean-checkout resumption. Plain-text `.superpowers` references elsewhere in this issue identify workspace-only historical receipts, not portable dependencies. Issue 094 remains OPEN; Needed 3 and both production Live guards remain closed.

---
title: "B4 D2 integrate pre-install confined helper readiness and retryable runtime retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer", "platform"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/PreparedOperationResources.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/PreparedHelperReadPlan.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/PreparedResourceSet.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperConfinement.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperControl.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperJob.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperMachine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperProtocol.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperRelay.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperLifetime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperMain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperSlot.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketOperation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemon.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/HelperSecurityObservation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/PreparedPath.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/ConfinedHelperChannel.kt"
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorHelperCredentials.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/PreparedLandlock.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperControlTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperRelayTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperClockTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperConfinementTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperProcessTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperPrewarmTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonEngineTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManagerTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonSharedStateTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/PreparedCloseOwnershipTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperReadinessIntegrationTest.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngineTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerDaemonEngine.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
  - "docs/internals/designs/enforcer/live-stack-scoping.md"
  - "docs/internals/backlog/README.md"
  - "docs/internals/backlog/issue-20261105-191554-457-preserve-queued-confined-helper-final-frames-acros.md"
  - "docs/internals/backlog/issue-20261105-202208-836-preserve-native-permission-errno-in-confined-helpe.md"
  - "docs/internals/backlog/issue-20261105-204738-628-release-confined-runtime-admission-after-queued-pr.md"
  - "docs/internals/backlog/issue-20261105-213711-642-retire-confined-conversation-after-terminal-accept.md"
  - "docs/internals/backlog/issue-20261105-213711-928-preserve-fatal-rollback-identity-after-ordinary-co.md"
  - "docs/internals/backlog/issue-20261105-213712-210-retain-unobserved-confined-drain-sweep-fatal-failu.md"
  - "docs/internals/backlog/issue-20261105-223351-669-preserve-prepared-resource-ownership-on-predispatc.md"
  - "docs/internals/backlog/issue-20261106-013649-338-retain-failed-prepared-acquisition-rollback-owners.md"
  - "docs/internals/backlog/issue-20261106-021835-047-assert-installed-no-new-privileges-state-in-confin.md"
  - "docs/internals/backlog/issue-20261106-035256-415-investigate-timed-out-consumer-stderr-loss-before-.md"
  - "docs/internals/backlog/issue-20261106-081044-009-qualify-reviewed-helper-runtime-closure-for-read-o.md"
  - "docs/internals/backlog/issue-20261106-081057-933-investigate-owned-worker-scoped-socket-denial-in-n.md"
  - "docs/internals/backlog/issue-20261106-161141-509-diagnose-wrapped-profiler-concurrent-read-future-t.md"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerIntegrationTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperProcessIntegrationTest.kt"
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidenceTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: HIGH]: B4 D2 integrate pre-install confined helper readiness and retryable runtime retirement

**Context:**
D1 is accepted; fixed2 D2 must connect the actual parent-daemon-helper readiness and preserve original owners through repeated ManagedRuntime.drain without replacement.

**Needed:**
1. Capture the dirty-checkout baseline and implement fixed2 D2 with compiling behavioral RED-GREEN tests, actual-child qualification and scoped review.

## Scope and acceptance

Execute accepted `b4-confined-helper-plan-fixed2.md` D2 and its shared N1/N2
contracts in the original roadmap workspace. The initial33 target paths plus this
issue formed the initial34-path scope. The operator subsequently authorized the
single shared `SupervisorHelperCredentials.kt` extension for typed peer-authentication
errno preservation (issue202208-836). Its saved dirty bytes/hash are in
`b4-d2-implementation-001/scope-extension-peer-001/`. Saved current bytes and4278 eligible-file states
are captured in `b4-d2-implementation-001/baseline/`; HEAD is identity, not the diff
base. The closed D1 artifact index verified before capture and stays historical.

Required outcomes: bounded pre-listener control without consuming legacy SCM byte0;
prestarted authenticated daemon/helper; actual Ready before worker install; retained
plan/domain/session ownership across failure; repeated actual ManagedRuntime.drain
with failed reap, shutdown races and no replacement. D1's required relay local cap
must survive every forwarding/stage wait. Tier1/H/worker ancestry observations must
account only for known installed layers and reject unknown differences.

Host fault tests do not qualify kernel effects. Untagged isolated real-child readiness
and retirement controls, review and the combined whole-B4 build remain acceptance
gates. No public ABI/default route, B5/B6 binding, ADDFD or new dependency. Keep all
changes uncommitted/unstaged/unpushed/unmerged and all prior attempts retained.

## Candidate implementation evidence

### Final private-envelope acceptance

The controller accepted D2 **with explicit limits**, following the bounded
[acceptance review055](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/b4-d2-final-acceptance055/review.md)
and [controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/b4-d2-final-acceptance055/controller-decision.md).
[Full build054](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/full-build-verification054/report.md)
passed with 4332 executed cases, zero failures/errors and 45 skips. The readiness
class passed all eight cases, with the relay ordering regression and retained-owner
controls included in the full run. Earlier failures remain historical evidence.

The three asynchronous profiler-parent endpoint shutdown errors remain unresolved
under existing issue20261003-131111-752. Individual profiler process/FD/pump
retirement is unknown; no cosmetic-only classification or global cleanup-success
claim is made. Independent triage056 did not establish a defect in the distinct B4
confined-enforcer-helper ownership path. The controller decision explains why that
follow-up does not invalidate the directly tested B4 controls.

D1's deferred minor, acquisition rollback issue20261106-013649-338, no-writer and
controlled-ancestry premises, skipped qualifications and broader Tier E proof gaps
remain. This accepts the private D2 implementation only: no public/native
activation, B5 release or general rootful qualification is implied. This update
does not archive the issue or change historical acceptance packets.

The private parent/daemon/helper path and helper-only retained runtime drain are
implemented. Connected host selection passed228/228 with zero skips/errors/failures.
Seven actual-runtime cases cover failed reap/retry/history, healthy repeated drain,
queued cancellation/full queue, unpublished preparation, in-flight timeout/interruption,
shutdown-before-termination/queued manager work, and exclusive preparation admission.
The owning static checks passed after the final integration-only formatting correction.

`b4-d2-implementation-001/readiness-rootful-006` passed8/8 isolated cases, no
skips/aborts/failures, within the authorized rootful/no-writer envelope. Every
acquired H physically exited; all negative cases had zero planned worker installs.
Root0 reached H and D closed its intermediate before root1; result receipt withheld
DONE and retained D/H descriptors until exact receipt/close completion. Parent
EXECVE was denied and late bootstrap refused. Controlled receipt/peer/time faults
are distinguished from actual extra worker Landlock and actual helper loss.

`scope-reconcile-001/delta.tsv` accounts for35 changed/added eligible paths against
the saved4278-path baseline. All executable changes stay within the initial scope
plus the approved credential-reader extension. The three additional issue paths
above are the required records for defects discovered and fixed during D2; they
introduce no additional executable scope. No removed path or unrelated delta was
observed; index is unstaged and HEAD unchanged.

Independent review, final-input qualification, combined whole-B4 build and closing
evidence reconciliation remain open. This issue is not yet resolved or archived.

## Independent review and expanded correction scope

The independent complete D2 snapshot review found three Important issues, recorded
as213711-642 (terminal accepted close disposal),213711-928 (fatal setup rollback)
and213712-210 (unobserved fatal drain sweep). R1 has compiling RED/GREEN through
actual platform cleanup and repeated runtime drain; the connected29 cases passed.
R2/R3 compiling regressions reproduced their exact failures; delegated candidate
fixes await controller GREEN verification. No acceptance follows from agent returns.

The first combined whole-B4 build failed at detektWarningRatchet with82added
normalized warnings. The operator explicitly approved extending source scope to
the five D1 helper files ConfinedHelperConfinement/Job/Machine/Protocol and
SocketHelperLifetime for behavior-preserving warning removal, without warning
inventory growth, forbidden suppressions or gate weakening.

The operator also approved connected PreparedPath/prepared-resource/operation/
Landlock owner and artifact files and owning tests needed to preserve actual
predispatch close ownership (issue223351-669). Terminal native-close generations
must never be reclosed. Known extra source bytes are captured before edits under
scope-extension-review001; additional connected files must be captured before
mutation. This explicit extension supersedes the earlier executable scope limit
only for those two bounded correction goals. All new defect issues are additive
required records; no unrelated feature scope or public behavior is authorized.

## Current correction and gate status

R1/R2/R3 reached behavioral RED→GREEN; connected checks passed268/268 enforcer
host cases,474/474 platform cases and32/32 profiler daemon cases. The profiler
engine addition is caller-only for the existing callback. The published-owner
predispatch close correction passed all four ownership tests; terminal native-close
generations are not reclosed. The warning ratchet reported zero added warnings.

Pre-publication acquisition rollback is a distinct, unqualified follow-up,
issue20261106-013649-338, deferred by operator decision; no new acquisition protocol
or retained-preparation registry is authorized here. The NNP characterization fix
is recorded in issue20261106-021835-047; strict other ancestry checks remain intact.
After the shared process-harness correction, readiness-rootful-010 passed8/8 and
helper-regression-rootful-004 passed18/18. The first loaded lifecycle class was
checked against the compiled snapshot. Later fixture-inventory changes still need
their own endpoint accounting; these controls do not substitute for full acceptance.

Whole-B4 host build002 failed. Isolated attempts001–005 failed on prerequisites or
snapshot bookkeeping;006 failed consumer timeout evidence. The operator approved
the narrow shared-harness correction: acquired-handle signals preserve output pipes,
with observed RED/GREEN, forced-path mutation coverage and independent review.
Focused verification passed49 consumer cases and50 root cases (one existing skip).

Attempts008/011 then hit the unchanged two-minute consumer build-logic deadline.
Gradle configuration-cache concurrency is not disabled by `--no-parallel` alone;
012 used one verified outer worker and passed all10 consumer build-logic cases.
It still failed18/232 enforcer integration cases (two skips):17 unaccounted shared
runtime mappings and one native socket-import denial. The operator approved both
bounded investigations. Parent/helper inode evidence justified a test-fixture-only
mapping inventory correction; production checks remain unchanged. Its focused
regression/lint and independent review passed; the27-case non-root selection then
passed19 helper and8 readiness cases with no skips/errors/failures under the
original syscall/capability envelope (run014, exited0/PID0/noOOM).

Historically, the socket path was authorized by policy but `pidfd_getfd` returned EPERM. An
invalid-fd probe also gets EPERM in the container versus EBADF on the host. The
operator conditionally approved a flags-zero container syscall allowance only after
baseline audit. Running-daemon policy equivalence could not be established, so no
replacement filter or added capability was used in that audit. Running-daemon policy
equivalence remains unproven.

The operator later selected official `moby/profiles/seccomp` v0.2.4 commit
`245180c51918481c0525424b3ee025d2b435d46c` as a new declared test baseline. The
controller independently matched its pinned JSON SHA-256
`785b2429264afba4d594320337cb17f144f3c7d51585f9805eef72e28f4f9334`. The reviewed
candidate appends only `ALLOW pidfd_getfd` when argument 2 is zero; its check and
independent review passed. Paired probes observed baseline flags 0/1 EPERM and
candidate flags 0 EBADF / flags 1 EPERM, with UID 1000, NNP 1 and all five capsets
CHECKPOINT_RESTORE only. The owned socket test passed 1/1 without skips, aborts or
failures; its candidate container exited 0/PID 0/no OOM. See
[the socket issue](issue-20261106-081057-933-investigate-owned-worker-scoped-socket-denial-in-n.md)
for evidence paths and the preserved audit. No library/source permission bypass or
added capability was used. Mapping014 and the shared-harness correction remain
focused evidence only. Full012 remains the last failed full gate; full015 has not
launched. Full acceptance remains pending, this issue stays open and unarchived,
and neither a default-built-in nor a default-parallel pass is claimed.

---
<!-- id: issue-20261105-175119-373-b4-d2-integrate-pre-install-confined-helper-readin  file: issue-20261105-175119-373-b4-d2-integrate-pre-install-confined-helper-readin.md -->
